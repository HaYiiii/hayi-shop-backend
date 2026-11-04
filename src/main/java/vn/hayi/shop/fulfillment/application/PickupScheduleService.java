package vn.hayi.shop.fulfillment.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.audit.api.AuditEntry;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.audit.api.AuditSource;
import vn.hayi.shop.branch.api.BranchDirectory;
import vn.hayi.shop.branch.api.BranchOpeningHours;
import vn.hayi.shop.branch.api.OpeningInterval;
import vn.hayi.shop.fulfillment.domain.BranchFulfillmentConfig;
import vn.hayi.shop.fulfillment.domain.DateOverride;
import vn.hayi.shop.fulfillment.domain.FulfillmentErrorCode;
import vn.hayi.shop.fulfillment.domain.FulfillmentType;
import vn.hayi.shop.fulfillment.domain.ScheduleOverride;
import vn.hayi.shop.fulfillment.domain.TimeWindow;
import vn.hayi.shop.fulfillment.domain.WeeklyWindow;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.time.BranchTime;

/**
 * Weekly pickup windows and date overrides of a Branch's config (API 09 §2.2, Decision N4, S). Branch facts come only
 * from {@code branch.api}; every change bumps the config version and is audited in the same transaction.
 */
@Service
public class PickupScheduleService {

	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	private final FulfillmentConfigStore configs;

	private final BranchDirectory branches;

	private final BranchOpeningHours openingHours;

	private final AuditRecorder audit;

	private final Clock clock;

	private final OverbookingCheck overbooking;

	PickupScheduleService(FulfillmentConfigStore configs, BranchDirectory branches, BranchOpeningHours openingHours,
			AuditRecorder audit, Clock clock, OverbookingCheck overbooking) {
		this.configs = configs;
		this.branches = branches;
		this.openingHours = openingHours;
		this.audit = audit;
		this.clock = clock;
		this.overbooking = overbooking;
	}

	@Transactional(readOnly = true)
	public PickupScheduleView get(UUID branchId, String fulfillmentType) {
		this.branches.requireExists(branchId);
		FulfillmentType type = FulfillmentConfigStore.supportedType(fulfillmentType);
		return view(this.configs.findOrDefault(branchId, type, this.clock.instant()), List.of());
	}

	/** Replaces the week; {@code expected} is required; {@code "v0"} also saves the default config (S1–S2). */
	@Transactional
	public PickupScheduleView replaceWeekly(UUID branchId, String fulfillmentType, List<ScheduleWindowInput> week,
			ExpectedVersion expected, ActorContext actor, String ipAddress, String userAgent) {
		BranchFulfillmentConfig config = editable(branchId, fulfillmentType);
		expected.verify(FulfillmentConfigStore.versionOf(config));
		List<WeeklyWindow> previous = config.getWeeklySchedule();
		boolean changed = config.replaceWeeklySchedule(week.stream()
			.map(window -> new WeeklyWindow(window.dayOfWeek(), new TimeWindow(window.start(), window.end()),
					window.maxOrders(), window.maxItems()))
			.toList(), day -> windows(this.openingHours.weekly(branchId, DayOfWeek.of(day))), this.clock.instant());
		if (!changed) {
			return view(config, this.overbooking.after(config));
		}
		this.configs.save(config);
		record(config, "FULFILLMENT_SCHEDULE_REPLACE", Map.of("weekly", weekly(previous)),
				Map.of("weekly", weekly(config.getWeeklySchedule())), actor, ipAddress, userAgent);
		return view(config, this.overbooking.after(config));
	}

	/**
	 * Sets one date's overrides, replacing its previous ones; {@code expected} is optional. The same hours again change
	 * nothing: no version bump, no Audit (S3).
	 */
	@Transactional
	public PickupScheduleView putOverride(UUID branchId, String fulfillmentType, OverrideInput input,
			ExpectedVersion expected, ActorContext actor, String ipAddress, String userAgent) {
		BranchFulfillmentConfig config = editable(branchId, fulfillmentType);
		if (expected != null) {
			expected.verify(FulfillmentConfigStore.versionOf(config));
		}
		DateOverride override = new DateOverride(input.date(), input.isClosed(),
				input.intervals().stream().map(window -> new TimeWindow(window.start(), window.end())).toList(),
				input.maxOrders(), input.maxItems(), input.reason());
		List<ScheduleOverride> previous = config.getOverridesOn(input.date());
		boolean changed = config.putOverride(override, today(), windows(this.openingHours.on(branchId, input.date())),
				this.clock.instant());
		if (!changed) {
			return view(config, this.overbooking.after(config));
		}
		this.configs.save(config);
		List<ScheduleOverride> current = config.getOverridesOn(input.date());
		record(config, "FULFILLMENT_OVERRIDE_PUT", overrideValue(input.date(), previous),
				overrideValue(input.date(), current), actor, ipAddress, userAgent);
		return view(config, this.overbooking.after(config));
	}

	/**
	 * Removes one override. An id that is unknown, or belongs to another Branch, gets the same
	 * {@code 404 FULFILLMENT_OVERRIDE_NOT_FOUND} (S7): the config is loaded by {@code branchId} and only its own
	 * overrides are searched.
	 */
	@Transactional
	public PickupScheduleView deleteOverride(UUID branchId, String fulfillmentType, UUID overrideId,
			ExpectedVersion expected, ActorContext actor, String ipAddress, String userAgent) {
		this.branches.requireExists(branchId);
		FulfillmentType type = FulfillmentConfigStore.supportedType(fulfillmentType);
		this.branches.requireConfigurable(branchId);
		BranchFulfillmentConfig config = this.configs.find(branchId, type)
			.orElseThrow(() -> new DomainException(FulfillmentErrorCode.FULFILLMENT_OVERRIDE_NOT_FOUND,
					"No config, so no override " + overrideId));
		if (expected != null) {
			expected.verify(config.getVersion());
		}
		ScheduleOverride removed = config.removeOverride(overrideId, this.clock.instant());
		this.configs.save(config);
		record(config, "FULFILLMENT_OVERRIDE_DELETE", overrideValue(removed.getDate(), List.of(removed)),
				Map.of("overrideId", overrideId.toString()), actor, ipAddress, userAgent);
		return view(config, this.overbooking.after(config));
	}

	private BranchFulfillmentConfig editable(UUID branchId, String fulfillmentType) {
		this.branches.requireExists(branchId);
		FulfillmentType type = FulfillmentConfigStore.supportedType(fulfillmentType);
		this.branches.requireConfigurable(branchId);
		return this.configs.findOrDefault(branchId, type, this.clock.instant());
	}

	private PickupScheduleView view(BranchFulfillmentConfig config, List<OverbookedSlotView> overbooked) {
		List<ScheduleWindowInput> weekly = config.getWeeklySchedule().stream()
			.map(window -> new ScheduleWindowInput(window.dayOfWeek(), window.window().start(), window.window().end(),
					window.maxOrders(), window.maxItems()))
			.toList();
		List<PickupScheduleView.Override> overrides = config.getOverridesFrom(today()).stream()
			.map(override -> new PickupScheduleView.Override(override.getId(), override.getDate(),
					override.isClosed(), (override.getWindow() != null) ? override.getWindow().start() : null,
					(override.getWindow() != null) ? override.getWindow().end() : null, override.getMaxOrders(),
					override.getMaxItems(), override.getReason()))
			.toList();
		return new PickupScheduleView(FulfillmentConfigStore.versionOf(config), weekly, overrides, overbooked);
	}

	private void record(BranchFulfillmentConfig config, String action, Map<String, ?> oldValue,
			Map<String, ?> newValue, ActorContext actor, String ipAddress, String userAgent) {
		this.audit.record(AuditEntry.of(actor, AuditSource.BACKOFFICE_WEB, action, "FULFILLMENT_CONFIG", config.getId())
			.withBranch(config.getBranchId())
			.withOldValue(oldValue)
			.withNewValue(newValue)
			.withClient(ipAddress, userAgent));
	}

	private static List<TimeWindow> windows(List<OpeningInterval> intervals) {
		return intervals.stream().map(interval -> new TimeWindow(interval.open(), interval.close())).toList();
	}

	private static List<Map<String, Object>> weekly(List<WeeklyWindow> week) {
		return week.stream().map(window -> {
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("dayOfWeek", window.dayOfWeek());
			value.put("start", time(window.window().start()));
			value.put("end", time(window.window().end()));
			value.put("maxOrders", window.maxOrders());
			value.put("maxItems", window.maxItems());
			return value;
		}).toList();
	}

	private static Map<String, Object> overrideValue(LocalDate date, List<ScheduleOverride> overrides) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("date", date.toString());
		value.put("overrides", overrides.stream().map(override -> {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("overrideId", override.getId().toString());
			entry.put("isClosed", override.isClosed());
			entry.put("start", (override.getWindow() != null) ? time(override.getWindow().start()) : null);
			entry.put("end", (override.getWindow() != null) ? time(override.getWindow().end()) : null);
			entry.put("maxOrders", override.getMaxOrders());
			entry.put("maxItems", override.getMaxItems());
			entry.put("reason", override.getReason());
			return entry;
		}).toList());
		return value;
	}

	private LocalDate today() {
		return BranchTime.localDate(this.clock.instant(), BranchTime.DEFAULT_ZONE);
	}

	private static String time(LocalTime time) {
		return time.format(HH_MM);
	}

}
