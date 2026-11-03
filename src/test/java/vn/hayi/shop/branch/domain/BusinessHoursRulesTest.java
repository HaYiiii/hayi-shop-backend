package vn.hayi.shop.branch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import vn.hayi.shop.branch.domain.BranchHoursInvalidException.Violation;

/** Decision Q2: ordered, non-overlapping ranges; overrides today or later, closed xor with ranges. */
class BusinessHoursRulesTest {

	static final LocalDate TODAY = LocalDate.of(2026, 11, 3);

	@Test
	void touchingRangesAreFineAndOtherDaysDoNotInterfere() {
		assertThatNoException().isThrownBy(() -> BusinessHoursRules.checkWeekly(List.of(week(1, 9, 12),
				week(1, 12, 15), week(2, 10, 14), week(1, 15, 23))));
	}

	@Test
	void overlapIsReportedOnTheLaterRangeEvenWhenListedFirst() {
		assertThat(weeklyViolations(week(3, 11, 14), week(3, 8, 12)))
			.containsExactly(new Violation("weekly[0]", "OVERLAPPING"));
	}

	@Test
	void rangeInsideAnotherOverlaps() {
		assertThat(weeklyViolations(week(4, 8, 20), week(4, 9, 10), week(4, 12, 13)))
			.containsExactly(new Violation("weekly[1]", "OVERLAPPING"), new Violation("weekly[2]", "OVERLAPPING"));
	}

	@Test
	void invertedAndEmptyRangesAreNotOrdered() {
		assertThat(weeklyViolations(week(5, 18, 8), week(5, 9, 9)))
			.containsExactly(new Violation("weekly[0]", "OPEN_NOT_BEFORE_CLOSE"),
					new Violation("weekly[1]", "OPEN_NOT_BEFORE_CLOSE"));
	}

	@Test
	void overrideOfTodayIsAllowedButNotOfYesterday() {
		assertThatNoException().isThrownBy(
				() -> BusinessHoursRules.checkOverride(new DayOverride(TODAY, true, List.of(), null), TODAY));
		BranchHoursInvalidException refused = catchThrowableOfType(BranchHoursInvalidException.class,
				() -> BusinessHoursRules.checkOverride(new DayOverride(TODAY.minusDays(1), true, List.of(), null),
						TODAY));
		assertThat(refused.violations()).containsExactly(new Violation("date", "DATE_IN_PAST"));
	}

	private static List<Violation> weeklyViolations(WeeklyHours... week) {
		return catchThrowableOfType(BranchHoursInvalidException.class,
				() -> BusinessHoursRules.checkWeekly(List.of(week)))
			.violations();
	}

	private static WeeklyHours week(int day, int open, int close) {
		return new WeeklyHours(day, new TimeRange(LocalTime.of(open, 0), LocalTime.of(close, 0)));
	}

}
