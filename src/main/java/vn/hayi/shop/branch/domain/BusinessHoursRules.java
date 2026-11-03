package vn.hayi.shop.branch.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import vn.hayi.shop.branch.domain.BranchHoursInvalidException.Violation;

/**
 * Decision Q2: every range has {@code open < close} (no overnight); ranges of one day do not overlap, touching is
 * fine; an override is for today or later and is either closed with no ranges or open with at least one.
 */
final class BusinessHoursRules {

	private BusinessHoursRules() {
	}

	static void checkWeekly(List<WeeklyHours> week) {
		List<Violation> violations = new ArrayList<>();
		for (int day = 1; day <= 7; day++) {
			int dayOfWeek = day;
			List<Integer> indexes = IntStream.range(0, week.size())
				.filter(i -> week.get(i).dayOfWeek() == dayOfWeek)
				.boxed()
				.toList();
			checkRanges(indexes, i -> week.get(i).range(), i -> "weekly[" + i + "]", violations);
		}
		throwIfAny(violations);
	}

	static void checkOverride(DayOverride override, LocalDate today) {
		List<Violation> violations = new ArrayList<>();
		if (override.date().isBefore(today)) {
			violations.add(new Violation("date", "DATE_IN_PAST"));
		}
		if (override.closed() && !override.ranges().isEmpty()) {
			violations.add(new Violation("intervals", "CLOSED_WITH_INTERVALS"));
		}
		else if (!override.closed() && override.ranges().isEmpty()) {
			violations.add(new Violation("intervals", "OPEN_WITHOUT_INTERVALS"));
		}
		else {
			List<Integer> indexes = IntStream.range(0, override.ranges().size()).boxed().toList();
			checkRanges(indexes, override.ranges()::get, i -> "intervals[" + i + "]", violations);
		}
		throwIfAny(violations);
	}

	/** Inverted ranges are reported as such and left out of the overlap check; an overlap is reported on the later one. */
	private static void checkRanges(List<Integer> indexes, IntFunction<TimeRange> range, IntFunction<String> field,
			List<Violation> violations) {
		List<Integer> ordered = new ArrayList<>();
		for (int index : indexes) {
			if (range.apply(index).isOrdered()) {
				ordered.add(index);
			}
			else {
				violations.add(new Violation(field.apply(index), "OPEN_NOT_BEFORE_CLOSE"));
			}
		}
		ordered.sort(Comparator.comparing((Integer i) -> range.apply(i).open()).thenComparing(i -> i));
		for (int k = 1; k < ordered.size(); k++) {
			for (int j = 0; j < k; j++) {
				if (range.apply(ordered.get(k)).overlaps(range.apply(ordered.get(j)))) {
					violations.add(new Violation(field.apply(ordered.get(k)), "OVERLAPPING"));
					break;
				}
			}
		}
	}

	private static void throwIfAny(List<Violation> violations) {
		if (!violations.isEmpty()) {
			violations.sort(Comparator.comparing(BusinessHoursRules::position).thenComparing(Violation::field));
			throw new BranchHoursInvalidException(violations);
		}
	}

	/** Errors read in request order: {@code date}, {@code intervals}, then by index. */
	private static int position(Violation violation) {
		String field = violation.field();
		int bracket = field.indexOf('[');
		return (bracket < 0) ? -1 : Integer.parseInt(field.substring(bracket + 1, field.length() - 1));
	}

}
