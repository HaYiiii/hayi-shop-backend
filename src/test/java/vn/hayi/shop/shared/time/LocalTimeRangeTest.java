package vn.hayi.shop.shared.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/** One day's {@code [start, end)} range, shared by Branch, Fulfillment and Catalog. */
class LocalTimeRangeTest {

	static LocalTimeRange range(String start, String end) {
		return new LocalTimeRange(LocalTime.parse(start), LocalTime.parse(end));
	}

	@Test
	void aRangeNeverRunsPastMidnight() {
		assertThat(range("07:00", "14:00").isOrdered()).isTrue();
		assertThat(range("22:00", "02:00").isOrdered()).isFalse();
		assertThat(range("10:00", "10:00").isOrdered()).isFalse();
	}

	@Test
	void touchingRangesDoNotOverlap() {
		assertThat(range("09:00", "12:00").overlaps(range("12:00", "15:00"))).isFalse();
		assertThat(range("09:00", "12:30").overlaps(range("12:00", "15:00"))).isTrue();
		assertThat(range("09:00", "15:00").contains(range("10:00", "12:00"))).isTrue();
	}

	@Test
	void theStartIsInsideAndTheEndIsNot() {
		assertThat(range("07:00", "14:00").contains(LocalTime.parse("07:00"))).isTrue();
		assertThat(range("07:00", "14:00").contains(LocalTime.parse("13:59:59"))).isTrue();
		assertThat(range("07:00", "14:00").contains(LocalTime.parse("14:00"))).isFalse();
	}

}
