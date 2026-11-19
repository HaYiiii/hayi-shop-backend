package vn.hayi.shop.catalog.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import vn.hayi.shop.shared.error.RuleViolationsException;
import vn.hayi.shop.shared.time.LocalTimeRange;

/**
 * One selling schedule as asked for or stored (Catalog §5.7, Decision AK1): an optional date range and daily
 * windows; no window means the whole day within the range.
 */
public record SellingScheduleSpec(LocalDate startDate, LocalDate endDate, List<Window> windows) {

	public static final int MAX_SCHEDULES = 10;

	public static final int MAX_WINDOWS = 20;

	public SellingScheduleSpec {
		windows = List.copyOf(windows);
	}

	/** {@code dayOfWeek} 1..7 (ISO), {@code null} = every day. */
	public record Window(Integer dayOfWeek, LocalTime start, LocalTime end) {

		LocalTimeRange range() {
			return new LocalTimeRange(this.start, this.end);
		}

		boolean appliesOn(DayOfWeek day) {
			return this.dayOfWeek == null || this.dayOfWeek == day.getValue();
		}

		/** Two windows can meet on a day when one applies every day or both apply on the same one. */
		boolean sharesADayWith(Window other) {
			return this.dayOfWeek == null || other.dayOfWeek == null || this.dayOfWeek.equals(other.dayOfWeek);
		}

	}

	/** Whether this schedule sells at that local date and time (AK2). */
	boolean sellsAt(LocalDate date, LocalTime time) {
		if ((this.startDate != null && date.isBefore(this.startDate))
				|| (this.endDate != null && date.isAfter(this.endDate))) {
			return false;
		}
		return this.windows.isEmpty() || this.windows.stream()
			.anyMatch(window -> window.appliesOn(date.getDayOfWeek()) && window.range().contains(time));
	}

	/**
	 * @throws RuleViolationsException {@code CATALOG_SELLING_SCHEDULE_INVALID}: a reversed date range, a window that
	 * does not end after it starts (never over midnight), or windows of one schedule that overlap on a day
	 */
	static void requireValid(List<SellingScheduleSpec> schedules) {
		List<RuleViolationsException.Violation> violations = new ArrayList<>();
		for (int i = 0; i < schedules.size(); i++) {
			SellingScheduleSpec schedule = schedules.get(i);
			String at = "schedules[" + i + "]";
			if (schedule.startDate != null && schedule.endDate != null && schedule.endDate.isBefore(schedule.startDate)) {
				violations.add(new RuleViolationsException.Violation(at + ".endDate", "RANGE"));
			}
			for (int j = 0; j < schedule.windows.size(); j++) {
				Window window = schedule.windows.get(j);
				if (!window.range().isOrdered()) {
					violations.add(new RuleViolationsException.Violation(at + ".windows[" + j + "]", "RANGE"));
					continue;
				}
				for (int k = 0; k < j; k++) {
					Window earlier = schedule.windows.get(k);
					if (earlier.range().isOrdered() && window.sharesADayWith(earlier)
							&& window.range().overlaps(earlier.range())) {
						violations.add(new RuleViolationsException.Violation(at + ".windows[" + j + "]", "OVERLAP"));
						break;
					}
				}
			}
		}
		if (!violations.isEmpty()) {
			throw new RuleViolationsException(CatalogErrorCode.CATALOG_SELLING_SCHEDULE_INVALID, violations);
		}
	}

}
