package vn.hayi.shop.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Slot generation and rating edges (Decision N1–N4, T3–T5). */
class SlotPlannerTest {

	static final LocalDate TOMORROW = LocalDate.of(2030, 1, 8);

	static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 7, 10, 0);

	@Test
	void lastSlotOfTheDayNeverRunsPastMidnight() {
		List<SlotCandidate> slots = SlotPlanner.slots(List.of(window(time(23, 0), time(23, 59), null, null)),
				List.of(range(time(0, 0), time(23, 59))), 30);

		assertThat(slots).extracting(slot -> slot.slot().start()).containsExactly(time(23, 0));
	}

	@Test
	void unlimitedCapacityIsNeverFull() {
		SlotCandidate unlimited = new SlotCandidate(range(time(9, 0), time(9, 30)), null, null);

		assertThat(SlotPlanner.availability(TOMORROW, unlimited, new UsedCapacity(10_000, 10_000), NOW, 0, 0, 50, 0.2))
			.isEqualTo(SlotAvailability.AVAILABLE);
	}

	@Test
	void nearlyFullStartsAtTheRatioExactly() {
		SlotCandidate ten = new SlotCandidate(range(time(9, 0), time(9, 30)), 10, null);

		assertThat(rate(ten, 7)).isEqualTo(SlotAvailability.AVAILABLE);
		assertThat(rate(ten, 8)).isEqualTo(SlotAvailability.NEARLY_FULL);
		assertThat(rate(ten, 10)).isEqualTo(SlotAvailability.FULL);
	}

	@Test
	void cutoffBoundaryIsInclusiveForTheCustomer() {
		// now ≤ slotStart − cutoff is still bookable (Decision N1): 13:00 − 180 = 10:00 = now.
		SlotCandidate at13 = new SlotCandidate(range(time(13, 0), time(13, 30)), null, null);

		assertThat(SlotPlanner.availability(NOW.toLocalDate(), at13, UsedCapacity.NONE, NOW, 180, 0, null, 0.2))
			.isEqualTo(SlotAvailability.AVAILABLE);
		assertThat(SlotPlanner.availability(NOW.toLocalDate(), at13, UsedCapacity.NONE, NOW.plusMinutes(1), 180, 0,
				null, 0.2))
			.isEqualTo(SlotAvailability.CUTOFF_PASSED);
	}

	private static SlotAvailability rate(SlotCandidate candidate, int usedOrders) {
		return SlotPlanner.availability(TOMORROW, candidate, new UsedCapacity(usedOrders, 0), NOW, 0, 0, null, 0.2);
	}

	private static CapacityWindow window(LocalTime start, LocalTime end, Integer maxOrders, Integer maxItems) {
		return new CapacityWindow(range(start, end), maxOrders, maxItems);
	}

	private static TimeWindow range(LocalTime start, LocalTime end) {
		return new TimeWindow(start, end);
	}

	private static LocalTime time(int hour, int minute) {
		return LocalTime.of(hour, minute);
	}

}
