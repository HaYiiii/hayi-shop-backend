package vn.hayi.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class MutableClockTest {

	@Test
	void frozenClockStandsStillUntilAdvancedOrReset() throws Exception {
		MutableClock clock = new MutableClock();
		clock.freeze();
		Instant frozen = clock.instant();

		Thread.sleep(5);
		assertThat(clock.instant()).isEqualTo(frozen);

		clock.advance(Duration.ofHours(1));
		assertThat(clock.instant()).isEqualTo(frozen.plus(Duration.ofHours(1)));

		clock.reset();
		Instant live = clock.instant();
		Thread.sleep(5);
		assertThat(clock.instant()).isAfter(live);
	}

}
