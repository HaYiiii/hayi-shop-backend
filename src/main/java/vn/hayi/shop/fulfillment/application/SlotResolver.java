package vn.hayi.shop.fulfillment.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.branch.api.BranchDirectory;
import vn.hayi.shop.branch.api.BranchOpeningHours;
import vn.hayi.shop.fulfillment.api.OrderType;
import vn.hayi.shop.fulfillment.api.PickupSlot;
import vn.hayi.shop.fulfillment.api.PickupSlots;
import vn.hayi.shop.fulfillment.api.SlotCalendar;
import vn.hayi.shop.fulfillment.api.SlotDay;
import vn.hayi.shop.fulfillment.api.SlotQuery;
import vn.hayi.shop.fulfillment.api.SlotStatus;
import vn.hayi.shop.fulfillment.domain.BranchFulfillmentConfig;
import vn.hayi.shop.fulfillment.domain.CapacityWindow;
import vn.hayi.shop.fulfillment.domain.FulfillmentSettings;
import vn.hayi.shop.fulfillment.domain.FulfillmentType;
import vn.hayi.shop.fulfillment.domain.SlotCandidate;
import vn.hayi.shop.fulfillment.domain.SlotKey;
import vn.hayi.shop.fulfillment.domain.SlotPlanner;
import vn.hayi.shop.fulfillment.domain.SlotUsage;
import vn.hayi.shop.fulfillment.domain.TimeWindow;
import vn.hayi.shop.fulfillment.domain.UsedCapacity;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.RuleViolationsException;
import vn.hayi.shop.shared.error.RuleViolationsException.Violation;
import vn.hayi.shop.shared.time.BranchTime;

/**
 * Resolves PICKUP slots (Fulfillment §8.3–8.5, Decision N1–N4, T) from the Branch's config and schedule, its actual
 * opening hours ({@code branch.api}) and the slot counters, as of the clock.
 */
@Service
class SlotResolver implements PickupSlots {

	private final BranchDirectory branches;

	private final BranchOpeningHours openingHours;

	private final FulfillmentConfigStore configs;

	private final SlotUsage usage;

	private final FulfillmentProperties properties;

	private final Clock clock;

	SlotResolver(BranchDirectory branches, BranchOpeningHours openingHours, FulfillmentConfigStore configs,
			SlotUsage usage, FulfillmentProperties properties, Clock clock) {
		this.branches = branches;
		this.openingHours = openingHours;
		this.configs = configs;
		this.usage = usage;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public SlotCalendar resolve(SlotQuery query) {
		this.branches.requireActive(query.branchId());
		LocalDateTime now = LocalDateTime.ofInstant(this.clock.instant(), BranchTime.DEFAULT_ZONE);
		LocalDate today = now.toLocalDate();
		Optional<BranchFulfillmentConfig> config = this.configs.find(query.branchId(), FulfillmentType.PICKUP);
		FulfillmentSettings settings = config.map(BranchFulfillmentConfig::settings)
			.orElse(FulfillmentSettings.DEFAULTS);
		LocalDate from = (query.fromDate() != null) ? query.fromDate() : today;
		int dayCount = (query.days() != null) ? query.days()
				: Math.min(SlotQuery.DEFAULT_DAYS, settings.maxAdvanceDays());
		List<SlotDay> days = new ArrayList<>();
		if (query.days() == null && dayCount == 0 && !from.isBefore(today)) {
			// No bookable day at all (maxAdvanceDays = 0) and none asked for explicitly (Decision T1).
			return new SlotCalendar(query.branchId(), days);
		}
		LocalDate to = from.plusDays(dayCount - 1L);
		checkHorizon(from, dayCount, to, today, settings.maxAdvanceDays());
		if (config.isEmpty() || !settings.enabled()) {
			from.datesUntil(to.plusDays(1)).forEach(date -> days.add(new SlotDay(date, false, List.of())));
			return new SlotCalendar(query.branchId(), days);
		}
		Map<SlotKey, UsedCapacity> used = this.usage.between(query.branchId(), FulfillmentType.PICKUP, from, to);
		int lead = (query.orderType() == OrderType.GROUP && settings.groupOrderMinLeadMinutes() != null)
				? settings.groupOrderMinLeadMinutes() : settings.minLeadMinutes();
		for (LocalDate date : from.datesUntil(to.plusDays(1)).toList()) {
			days.add(day(query.branchId(), config.get(), date, now, lead, used, query.itemCount()));
		}
		return new SlotCalendar(query.branchId(), days);
	}

	@Override
	@Transactional(readOnly = true)
	public SlotDay capacityOn(UUID branchId, LocalDate date) {
		this.branches.requireExists(branchId);
		Optional<BranchFulfillmentConfig> config = this.configs.find(branchId, FulfillmentType.PICKUP);
		if (config.isEmpty()) {
			return new SlotDay(date, false, List.of());
		}
		LocalDateTime now = LocalDateTime.ofInstant(this.clock.instant(), BranchTime.DEFAULT_ZONE);
		Map<SlotKey, UsedCapacity> used = this.usage.between(branchId, FulfillmentType.PICKUP, date, date);
		return day(branchId, config.get(), date, now, config.get().settings().minLeadMinutes(), used, null);
	}

	/**
	 * The slots {@code config} generates on {@code date}, cut to that day's Branch hours (Decision N4, T3); the one
	 * place slots are generated, for customers, staff and the overbooking check alike.
	 */
	List<SlotCandidate> candidatesOn(UUID branchId, BranchFulfillmentConfig config, LocalDate date) {
		List<CapacityWindow> windows = config.windowsOn(date);
		if (windows.isEmpty()) {
			return List.of();
		}
		List<TimeWindow> branchHours = this.openingHours.on(branchId, date).stream()
			.map(interval -> new TimeWindow(interval.open(), interval.close()))
			.toList();
		return SlotPlanner.slots(windows, branchHours, config.settings().slotDurationMinutes());
	}

	private SlotDay day(UUID branchId, BranchFulfillmentConfig config, LocalDate date, LocalDateTime now, int lead,
			Map<SlotKey, UsedCapacity> used, Integer itemCount) {
		FulfillmentSettings settings = config.settings();
		List<PickupSlot> slots = candidatesOn(branchId, config, date).stream()
			.map(candidate -> slot(date, candidate,
					used.getOrDefault(new SlotKey(date, candidate.slot().start()), UsedCapacity.NONE), now,
					settings.sameDayCutoffMinutes(), lead, itemCount))
			.toList();
		return new SlotDay(date, !slots.isEmpty(), slots);
	}

	private PickupSlot slot(LocalDate date, SlotCandidate candidate, UsedCapacity used, LocalDateTime now,
			int cutoff, int lead, Integer itemCount) {
		SlotStatus status = SlotStatus.valueOf(SlotPlanner
			.availability(date, candidate, used, now, cutoff, lead, itemCount, this.properties.nearlyFullRatio())
			.name());
		return new PickupSlot(candidate.slot().start(), candidate.slot().end(), status, candidate.maxOrders(),
				candidate.maxItems(), used.orders(), used.items());
	}

	/** Decision T1: {@code maxAdvanceDays} days including today. */
	private static void checkHorizon(LocalDate from, int days, LocalDate to, LocalDate today, int maxAdvanceDays) {
		List<Violation> violations = new ArrayList<>();
		if (from.isBefore(today)) {
			violations.add(new Violation("fromDate", "DATE_IN_PAST"));
		}
		if (days < 1) {
			violations.add(new Violation("days", "BELOW_ONE"));
		}
		else if (to.isAfter(today.plusDays(maxAdvanceDays - 1L))) {
			violations.add(new Violation("days", "BEYOND_HORIZON"));
		}
		if (!violations.isEmpty()) {
			throw new RuleViolationsException(CommonErrorCode.VALIDATION_FAILED, violations);
		}
	}

}
