package vn.hayi.shop.fulfillment.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import vn.hayi.shop.shared.error.RuleViolationsException;
import vn.hayi.shop.shared.error.RuleViolationsException.Violation;

/**
 * Decision S5–S6, one code per answer, in order: invalid values → {@code FULFILLMENT_CONFIG_INVALID}; overlapping
 * windows of one day (touching is fine) → {@code FULFILLMENT_SCHEDULE_OVERLAP}; a window not inside one continuous
 * open stretch of the Branch → {@code FULFILLMENT_OUTSIDE_BRANCH_HOURS}.
 */
final class ScheduleRules {

	private ScheduleRules() {
	}

	/** {@code branchHours} gives the Branch's weekly open ranges of an ISO day of week. */
	static void checkWeekly(List<WeeklyWindow> week, IntFunction<List<TimeWindow>> branchHours) {
		List<Violation> invalid = new ArrayList<>();
		for (int i = 0; i < week.size(); i++) {
			WeeklyWindow window = week.get(i);
			if (!window.window().isOrdered()) {
				invalid.add(new Violation("weekly[" + i + "]", "START_NOT_BEFORE_END"));
			}
			capacity(window.maxOrders(), "weekly[" + i + "].maxOrders", invalid);
			capacity(window.maxItems(), "weekly[" + i + "].maxItems", invalid);
		}
		throwIfAny(FulfillmentErrorCode.FULFILLMENT_CONFIG_INVALID, invalid);
		List<Violation> overlapping = new ArrayList<>();
		List<Violation> outside = new ArrayList<>();
		for (int day = 1; day <= 7; day++) {
			int dayOfWeek = day;
			List<Integer> indexes = IntStream.range(0, week.size())
				.filter(i -> week.get(i).dayOfWeek() == dayOfWeek)
				.boxed()
				.toList();
			if (indexes.isEmpty()) {
				continue;
			}
			overlaps(indexes, i -> week.get(i).window(), i -> "weekly[" + i + "]", overlapping);
			outside(indexes, i -> week.get(i).window(), i -> "weekly[" + i + "]", branchHours.apply(dayOfWeek),
					outside);
		}
		throwIfAny(FulfillmentErrorCode.FULFILLMENT_SCHEDULE_OVERLAP, overlapping);
		throwIfAny(FulfillmentErrorCode.FULFILLMENT_OUTSIDE_BRANCH_HOURS, outside);
	}

	/** {@code branchHours} are the Branch's actual open ranges on the override's date (Decision Q5). */
	static void checkOverride(DateOverride override, LocalDate today, List<TimeWindow> branchHours) {
		List<Violation> invalid = new ArrayList<>();
		if (override.date().isBefore(today)) {
			invalid.add(new Violation("date", "DATE_IN_PAST"));
		}
		if (override.closed() && !override.windows().isEmpty()) {
			invalid.add(new Violation("intervals", "CLOSED_WITH_INTERVALS"));
		}
		else if (!override.closed() && override.windows().isEmpty()) {
			invalid.add(new Violation("intervals", "OPEN_WITHOUT_INTERVALS"));
		}
		capacity(override.maxOrders(), "maxOrders", invalid);
		capacity(override.maxItems(), "maxItems", invalid);
		for (int i = 0; i < override.windows().size(); i++) {
			if (!override.windows().get(i).isOrdered()) {
				invalid.add(new Violation("intervals[" + i + "]", "START_NOT_BEFORE_END"));
			}
		}
		throwIfAny(FulfillmentErrorCode.FULFILLMENT_CONFIG_INVALID, invalid);
		if (override.closed()) {
			return;
		}
		List<Integer> indexes = IntStream.range(0, override.windows().size()).boxed().toList();
		List<Violation> overlapping = new ArrayList<>();
		overlaps(indexes, override.windows()::get, i -> "intervals[" + i + "]", overlapping);
		throwIfAny(FulfillmentErrorCode.FULFILLMENT_SCHEDULE_OVERLAP, overlapping);
		List<Violation> outside = new ArrayList<>();
		outside(indexes, override.windows()::get, i -> "intervals[" + i + "]", branchHours, outside);
		throwIfAny(FulfillmentErrorCode.FULFILLMENT_OUTSIDE_BRANCH_HOURS, outside);
	}

	/** An overlap is reported on the later window (by start, then position). */
	private static void overlaps(List<Integer> indexes, IntFunction<TimeWindow> window, IntFunction<String> field,
			List<Violation> violations) {
		List<Integer> ordered = new ArrayList<>(indexes);
		ordered.sort(Comparator.comparing((Integer i) -> window.apply(i).start()).thenComparing(i -> i));
		for (int k = 1; k < ordered.size(); k++) {
			for (int j = 0; j < k; j++) {
				if (window.apply(ordered.get(k)).overlaps(window.apply(ordered.get(j)))) {
					violations.add(new Violation(field.apply(ordered.get(k)), "OVERLAPPING"));
					break;
				}
			}
		}
		violations.sort(Comparator.comparing(Violation::field, Comparator.comparingInt(ScheduleRules::index)));
	}

	private static void outside(List<Integer> indexes, IntFunction<TimeWindow> window, IntFunction<String> field,
			List<TimeWindow> branchHours, List<Violation> violations) {
		List<TimeWindow> stretches = continuous(branchHours);
		for (int index : indexes) {
			TimeWindow candidate = window.apply(index);
			if (stretches.stream().noneMatch(stretch -> stretch.contains(candidate))) {
				violations.add(new Violation(field.apply(index), "OUTSIDE_BRANCH_HOURS"));
			}
		}
		violations.sort(Comparator.comparing(Violation::field, Comparator.comparingInt(ScheduleRules::index)));
	}

	/** Branch ranges that touch or overlap are one open stretch ({@code 07–11} + {@code 11–14} = {@code 07–14}). */
	static List<TimeWindow> continuous(List<TimeWindow> ranges) {
		List<TimeWindow> sorted = ranges.stream().sorted(Comparator.comparing(TimeWindow::start)).toList();
		List<TimeWindow> merged = new ArrayList<>();
		for (TimeWindow range : sorted) {
			if (!merged.isEmpty() && !range.start().isAfter(merged.getLast().end())) {
				TimeWindow last = merged.removeLast();
				merged.add(new TimeWindow(last.start(), range.end().isAfter(last.end()) ? range.end() : last.end()));
			}
			else {
				merged.add(range);
			}
		}
		return merged;
	}

	private static void capacity(Integer value, String field, List<Violation> violations) {
		if (value != null && value < 1) {
			violations.add(new Violation(field, "BELOW_ONE"));
		}
	}

	private static int index(String field) {
		int bracket = field.indexOf('[');
		return (bracket < 0) ? -1 : Integer.parseInt(field.substring(bracket + 1, field.indexOf(']')));
	}

	private static void throwIfAny(FulfillmentErrorCode code, List<Violation> violations) {
		if (!violations.isEmpty()) {
			throw new RuleViolationsException(code, violations);
		}
	}

}
