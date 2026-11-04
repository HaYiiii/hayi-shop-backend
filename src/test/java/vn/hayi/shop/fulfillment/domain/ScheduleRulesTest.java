package vn.hayi.shop.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import vn.hayi.shop.shared.error.RuleViolationsException;
import vn.hayi.shop.shared.error.RuleViolationsException.Violation;

/** Decision S5–S6: one code per answer; windows inside one continuous open stretch of the Branch. */
class ScheduleRulesTest {

	@Test
	void touchingAndOverlappingBranchRangesAreOneStretch() {
		assertThat(ScheduleRules.continuous(List.of(range(13, 21), range(7, 11), range(11, 14), range(22, 23))))
			.containsExactly(range(7, 21), range(22, 23));
	}

	@Test
	void windowAcrossAGapIsOutside() {
		RuleViolationsException refused = weeklyRefusal(List.of(window(1, 10, 14)),
				List.of(range(7, 11), range(13, 21)));

		assertThat(refused.code()).isEqualTo(FulfillmentErrorCode.FULFILLMENT_OUTSIDE_BRANCH_HOURS);
		assertThat(refused.violations()).containsExactly(new Violation("weekly[0]", "OUTSIDE_BRANCH_HOURS"));
	}

	@Test
	void overlapIsReportedBeforeOutsideHours() {
		RuleViolationsException refused = weeklyRefusal(List.of(window(1, 5, 9), window(1, 8, 10)),
				List.of(range(7, 21)));

		assertThat(refused.code()).isEqualTo(FulfillmentErrorCode.FULFILLMENT_SCHEDULE_OVERLAP);
		assertThat(refused.violations()).containsExactly(new Violation("weekly[1]", "OVERLAPPING"));
	}

	@Test
	void touchingWindowsDoNotOverlap() {
		assertThatNoException().isThrownBy(() -> ScheduleRules
			.checkWeekly(List.of(window(2, 9, 12), window(2, 12, 15)), day -> List.of(range(7, 21))));
	}

	private static RuleViolationsException weeklyRefusal(List<WeeklyWindow> week, List<TimeWindow> branchHours) {
		return catchThrowableOfType(RuleViolationsException.class,
				() -> ScheduleRules.checkWeekly(week, day -> branchHours));
	}

	private static WeeklyWindow window(int day, int start, int end) {
		return new WeeklyWindow(day, range(start, end), null, null);
	}

	private static TimeWindow range(int start, int end) {
		return new TimeWindow(LocalTime.of(start, 0), LocalTime.of(end, 0));
	}

}
