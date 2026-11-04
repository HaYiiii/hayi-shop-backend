package vn.hayi.shop.branch.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.audit.api.AuditEntry;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.audit.api.AuditSource;
import vn.hayi.shop.branch.api.BranchOpeningHours;
import vn.hayi.shop.branch.api.OpeningInterval;
import vn.hayi.shop.branch.domain.Branch;
import vn.hayi.shop.branch.domain.BranchErrorCode;
import vn.hayi.shop.branch.domain.BranchRepository;
import vn.hayi.shop.branch.domain.DayOverride;
import vn.hayi.shop.branch.domain.TimeRange;
import vn.hayi.shop.branch.domain.WeeklyHours;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.time.BranchTime;

/**
 * Weekly hours and date overrides of a Branch (Branch §4.4, API 09 §1.4, Decision Q). Each change bumps the Branch
 * version and is audited in the same transaction; also answers {@link BranchOpeningHours} for other modules.
 */
@Service
public class BranchHoursService implements BranchOpeningHours {

	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	private final BranchRepository branches;

	private final AuditRecorder audit;

	private final Clock clock;

	BranchHoursService(BranchRepository branches, AuditRecorder audit, Clock clock) {
		this.branches = branches;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public BranchHoursView get(UUID branchId) {
		return view(find(branchId));
	}

	/**
	 * Replaces the whole week; {@code expected} is required (API README §8). The same week again changes nothing: no
	 * version bump, no Audit (Decision Q1).
	 */
	@Transactional
	public BranchHoursView replaceWeekly(UUID branchId, List<WeeklyIntervalRequest> week, ExpectedVersion expected,
			ActorContext actor, String ipAddress, String userAgent) {
		Branch branch = find(branchId);
		expected.verify(branch.getVersion());
		List<WeeklyHours> previous = branch.getWeeklyHours();
		boolean changed = branch.replaceWeeklyHours(week.stream()
			.map(hours -> new WeeklyHours(hours.dayOfWeek(), new TimeRange(hours.open(), hours.close())))
			.toList(), this.clock.instant());
		if (!changed) {
			return view(branch);
		}
		this.branches.saveAndFlush(branch);
		record(branch, "BRANCH_HOURS_REPLACE", Map.of("weekly", weekly(previous)),
				Map.of("weekly", weekly(branch.getWeeklyHours())), actor, ipAddress, userAgent);
		return view(branch);
	}

	/** Sets one date's hours, replacing its override; {@code expected} is optional ({@code null}). */
	@Transactional
	public BranchHoursView putOverride(UUID branchId, HourOverrideRequest request, ExpectedVersion expected,
			ActorContext actor, String ipAddress, String userAgent) {
		Branch branch = find(branchId);
		if (expected != null) {
			expected.verify(branch.getVersion());
		}
		DayOverride override = new DayOverride(request.date(), request.isClosed(),
				request.intervals().stream().map(range -> new TimeRange(range.open(), range.close())).toList(),
				request.reason());
		Optional<DayOverride> previous = branch.putOverride(override, today(), this.clock.instant());
		this.branches.saveAndFlush(branch);
		record(branch, "BRANCH_HOUR_OVERRIDE_PUT", overrideValue(request.date(), previous.orElse(null)),
				overrideValue(request.date(), override), actor, ipAddress, userAgent);
		return view(branch);
	}

	/** Removes one date's override; {@code expected} is optional ({@code null}). */
	@Transactional
	public BranchHoursView deleteOverride(UUID branchId, LocalDate date, ExpectedVersion expected, ActorContext actor,
			String ipAddress, String userAgent) {
		Branch branch = find(branchId);
		if (expected != null) {
			expected.verify(branch.getVersion());
		}
		DayOverride removed = branch.removeOverride(date, this.clock.instant());
		this.branches.saveAndFlush(branch);
		record(branch, "BRANCH_HOUR_OVERRIDE_DELETE", overrideValue(date, removed), overrideValue(date, null), actor,
				ipAddress, userAgent);
		return view(branch);
	}

	@Override
	@Transactional(readOnly = true)
	public List<OpeningInterval> on(UUID branchId, LocalDate date) {
		return this.branches.findById(branchId)
			.map(branch -> branch.openingHoursOn(date).stream().map(BranchHoursService::interval).toList())
			.orElse(List.of());
	}

	@Override
	@Transactional(readOnly = true)
	public List<OpeningInterval> weekly(UUID branchId, DayOfWeek day) {
		return this.branches.findById(branchId)
			.map(branch -> branch.getWeeklyHours().stream()
				.filter(hours -> hours.dayOfWeek() == day.getValue())
				.map(hours -> interval(hours.range()))
				.toList())
			.orElse(List.of());
	}

	private BranchHoursView view(Branch branch) {
		List<WeeklyIntervalRequest> weekly = branch.getWeeklyHours().stream()
			.map(hours -> new WeeklyIntervalRequest(hours.dayOfWeek(), hours.range().open(), hours.range().close()))
			.toList();
		List<BranchHoursView.Override> overrides = branch.getOverridesFrom(today()).stream()
			.map(override -> new BranchHoursView.Override(override.date(), override.closed(),
					override.ranges().stream().map(BranchHoursService::interval).toList(), override.reason()))
			.toList();
		return new BranchHoursView(branch.getVersion(), weekly, overrides);
	}

	private void record(Branch branch, String action, Map<String, ?> oldValue, Map<String, ?> newValue,
			ActorContext actor, String ipAddress, String userAgent) {
		this.audit.record(AuditEntry.of(actor, AuditSource.BACKOFFICE_WEB, action, "BRANCH", branch.getId())
			.withBranch(branch.getId())
			.withOldValue(oldValue)
			.withNewValue(newValue)
			.withClient(ipAddress, userAgent));
	}

	private static List<Map<String, Object>> weekly(List<WeeklyHours> week) {
		return week.stream().map(hours -> {
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("dayOfWeek", hours.dayOfWeek());
			value.put("open", time(hours.range().open()));
			value.put("close", time(hours.range().close()));
			return value;
		}).toList();
	}

	/** {@code {date, override}}, {@code override} being {@code null} when the date has none. */
	private static Map<String, Object> overrideValue(LocalDate date, DayOverride override) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("date", date.toString());
		if (override == null) {
			value.put("override", null);
			return value;
		}
		Map<String, Object> hours = new LinkedHashMap<>();
		hours.put("isClosed", override.closed());
		hours.put("intervals", override.ranges().stream()
			.map(range -> Map.of("open", time(range.open()), "close", time(range.close())))
			.toList());
		hours.put("reason", override.reason());
		value.put("override", hours);
		return value;
	}

	private LocalDate today() {
		return BranchTime.localDate(this.clock.instant(), BranchTime.DEFAULT_ZONE);
	}

	private Branch find(UUID branchId) {
		return this.branches.findById(branchId)
			.orElseThrow(() -> new DomainException(BranchErrorCode.BRANCH_NOT_FOUND, "No Branch " + branchId));
	}

	private static OpeningInterval interval(TimeRange range) {
		return new OpeningInterval(range.open(), range.close());
	}

	private static String time(LocalTime time) {
		return time.format(HH_MM);
	}

}
