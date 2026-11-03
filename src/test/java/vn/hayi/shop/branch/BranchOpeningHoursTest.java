package vn.hayi.shop.branch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.branch.api.BranchOpeningHours;
import vn.hayi.shop.branch.api.OpeningInterval;
import vn.hayi.shop.branch.application.BranchHoursService;
import vn.hayi.shop.branch.application.BranchService;
import vn.hayi.shop.branch.application.HourOverrideRequest;
import vn.hayi.shop.branch.application.NewBranch;
import vn.hayi.shop.branch.application.WeeklyIntervalRequest;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.time.BranchTime;

/**
 * Other modules (Fulfillment) ask {@code branch.api} for a Branch's actual opening hours on a date: the override of
 * that date wins over the weekly schedule; no schedule means closed (Branch §4.4, Decision Q5).
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class })
class BranchOpeningHoursTest {

	static final ActorContext OWNER = ActorContext.staff(UUID.randomUUID(), null);

	@Autowired
	BranchOpeningHours openingHours;

	@Autowired
	BranchService branches;

	@Autowired
	BranchHoursService hours;

	@Autowired
	MutableClock clock;

	UUID branchId;

	LocalDate nextMonday;

	@BeforeEach
	void setUp() {
		this.branchId = this.branches.create(new NewBranch("T-" + UUID.randomUUID().toString().substring(0, 8)
			.toUpperCase(), "HaYi test", "1 Lê Lợi", null, null, "TP.HCM", null, null, null, null), OWNER, null, null)
			.branchId();
		this.nextMonday = LocalDate.ofInstant(this.clock.instant(), BranchTime.DEFAULT_ZONE)
			.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
		this.hours.replaceWeekly(this.branchId, List.of(new WeeklyIntervalRequest(1, at(7), at(11)),
				new WeeklyIntervalRequest(1, at(13), at(21))), new ExpectedVersion(0), OWNER, null, null);
	}

	@Test
	void weeklyHoursApplyOnTheirDay() {
		assertThat(this.openingHours.on(this.branchId, this.nextMonday))
			.containsExactly(new OpeningInterval(at(7), at(11)), new OpeningInterval(at(13), at(21)));
	}

	@Test
	void dayWithoutScheduleIsClosed() {
		assertThat(this.openingHours.on(this.branchId, this.nextMonday.plusDays(1))).isEmpty();
	}

	@Test
	void overrideWinsOverWeeklyHours() {
		this.hours.putOverride(this.branchId,
				new HourOverrideRequest(this.nextMonday, false, List.of(new OpeningInterval(at(9), at(23))), "Lễ"),
				null, OWNER, null, null);

		assertThat(this.openingHours.on(this.branchId, this.nextMonday))
			.containsExactly(new OpeningInterval(at(9), at(23)));
		assertThat(this.openingHours.on(this.branchId, this.nextMonday.plusWeeks(1))).hasSize(2);
	}

	@Test
	void closedOverrideClosesTheDay() {
		this.hours.putOverride(this.branchId, new HourOverrideRequest(this.nextMonday, true, List.of(), null), null,
				OWNER, null, null);

		assertThat(this.openingHours.on(this.branchId, this.nextMonday)).isEmpty();
	}

	@Test
	void unknownBranchIsClosed() {
		assertThat(this.openingHours.on(UUID.randomUUID(), this.nextMonday)).isEmpty();
	}

	private static LocalTime at(int hour) {
		return LocalTime.of(hour, 0);
	}

}
