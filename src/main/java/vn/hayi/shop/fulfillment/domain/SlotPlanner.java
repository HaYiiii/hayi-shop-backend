package vn.hayi.shop.fulfillment.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generates and rates PICKUP slots (Fulfillment §8.3–8.5, Decision N1–N4, T3–T5). Pure: everything it needs is
 * passed in.
 */
public final class SlotPlanner {

	private SlotPlanner() {
	}

	/**
	 * Consecutive slots of {@code slotMinutes} from the start of each window, the tail that does not fit dropped, and
	 * only those lying wholly inside one continuous open stretch of the Branch that day (N4, S5).
	 */
	public static List<SlotCandidate> slots(List<CapacityWindow> windows, List<TimeWindow> branchHours,
			int slotMinutes) {
		List<TimeWindow> stretches = ScheduleRules.continuous(branchHours);
		List<SlotCandidate> slots = new ArrayList<>();
		for (CapacityWindow window : windows) {
			LocalTime start = window.window().start();
			while (true) {
				LocalTime end = start.plusMinutes(slotMinutes);
				// A slot never runs past midnight (no overnight windows) nor past the window's end.
				if (!end.isAfter(start) || end.isAfter(window.window().end())) {
					break;
				}
				TimeWindow slot = new TimeWindow(start, end);
				if (stretches.stream().anyMatch(stretch -> stretch.contains(slot))) {
					slots.add(new SlotCandidate(slot, window.maxOrders(), window.maxItems()));
				}
				start = end;
			}
		}
		slots.sort(Comparator.comparing(candidate -> candidate.slot().start()));
		return slots;
	}

	/**
	 * Decision T4–T5 with N1/N2: past the same-day cutoff (today only) or short of the lead time (every day), then
	 * full on orders or on {@code max(itemCount, 1)} items, then nearly full when either dimension has at most
	 * {@code nearlyFullRatio} of its maximum left.
	 */
	public static SlotAvailability availability(LocalDate date, SlotCandidate candidate, UsedCapacity used,
			LocalDateTime now, int sameDayCutoffMinutes, int leadMinutes, Integer itemCount, double nearlyFullRatio) {
		LocalDateTime start = date.atTime(candidate.slot().start());
		boolean pastCutoff = date.equals(now.toLocalDate()) && now.isAfter(start.minusMinutes(sameDayCutoffMinutes));
		boolean shortOfLead = start.isBefore(now.plusMinutes(leadMinutes));
		if (pastCutoff || shortOfLead) {
			return SlotAvailability.CUTOFF_PASSED;
		}
		int wantedItems = Math.max((itemCount != null) ? itemCount : 1, 1);
		boolean full = left(candidate.maxOrders(), used.orders()) < 1
				|| left(candidate.maxItems(), used.items()) < wantedItems;
		if (full) {
			return SlotAvailability.FULL;
		}
		boolean nearlyFull = nearlyFull(candidate.maxOrders(), used.orders(), nearlyFullRatio)
				|| nearlyFull(candidate.maxItems(), used.items(), nearlyFullRatio);
		return nearlyFull ? SlotAvailability.NEARLY_FULL : SlotAvailability.AVAILABLE;
	}

	private static long left(Integer max, int used) {
		return (max != null) ? (long) max - used : Long.MAX_VALUE;
	}

	private static boolean nearlyFull(Integer max, int used, double ratio) {
		return max != null && max - used <= ratio * max;
	}

}
