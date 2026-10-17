package vn.hayi.shop.shared.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class BranchTimeTest {

	@Test
	void lateEveningUtcIsAlreadyNextOperationalDayInVietnam() {
		Instant instant = Instant.parse("2026-10-14T17:30:00Z");

		assertThat(BranchTime.localDate(instant, BranchTime.DEFAULT_ZONE)).isEqualTo(LocalDate.of(2026, 10, 15));
	}

	@Test
	void operationalSlotStartConvertsToUtcInstant() {
		Instant instant = BranchTime.toInstant(LocalDate.of(2026, 10, 12), LocalTime.of(9, 0), BranchTime.DEFAULT_ZONE);

		assertThat(instant).isEqualTo(Instant.parse("2026-10-12T02:00:00Z"));
	}

}
