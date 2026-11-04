package vn.hayi.shop.fulfillment.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import vn.hayi.shop.fulfillment.domain.BranchFulfillmentConfig;
import vn.hayi.shop.fulfillment.domain.ClaimedSlot;
import vn.hayi.shop.fulfillment.domain.FulfillmentType;
import vn.hayi.shop.fulfillment.domain.SlotCandidate;
import vn.hayi.shop.fulfillment.domain.SlotUsage;
import vn.hayi.shop.shared.time.BranchTime;

/**
 * After a config, schedule or override change: the slots not started yet whose claims now exceed their capacity, or
 * that are no longer generated at all (max {@code 0}); a slot that has started is beyond the change's reach. Only
 * reports; nothing claimed is cancelled or moved (Fulfillment §8.5, Decision V2). Runs after the change is flushed,
 * with the same slot generation as the resolver.
 */
@Component
class OverbookingCheck {

	private final SlotUsage usage;

	private final SlotResolver resolver;

	private final Clock clock;

	OverbookingCheck(SlotUsage usage, SlotResolver resolver, Clock clock) {
		this.usage = usage;
		this.resolver = resolver;
		this.clock = clock;
	}

	List<OverbookedSlotView> after(BranchFulfillmentConfig config) {
		LocalDateTime now = LocalDateTime.ofInstant(this.clock.instant(), BranchTime.DEFAULT_ZONE);
		Map<LocalDate, List<SlotCandidate>> slotsByDate = new HashMap<>();
		List<OverbookedSlotView> overbooked = new ArrayList<>();
		for (ClaimedSlot claimed : this.usage.claimedFrom(config.getBranchId(), FulfillmentType.PICKUP,
				now.toLocalDate())) {
			if (!claimed.date().atTime(claimed.slot().start()).isAfter(now)) {
				continue;
			}
			List<SlotCandidate> slots = slotsByDate.computeIfAbsent(claimed.date(),
					date -> this.resolver.candidatesOn(config.getBranchId(), config, date));
			Optional<SlotCandidate> slot = slots.stream()
				.filter(candidate -> candidate.slot().equals(claimed.slot()))
				.findFirst();
			Integer maxOrders = slot.map(SlotCandidate::maxOrders).orElse(0);
			Integer maxItems = slot.map(SlotCandidate::maxItems).orElse(0);
			if (exceeds(claimed.used().orders(), maxOrders) || exceeds(claimed.used().items(), maxItems)) {
				overbooked.add(new OverbookedSlotView(claimed.date(), claimed.slot().start(), claimed.slot().end(),
						claimed.used().orders(), maxOrders, claimed.used().items(), maxItems));
			}
		}
		return overbooked;
	}

	/** {@code null} max = no limit; a slot that is gone was given max 0 by the caller. */
	private static boolean exceeds(int used, Integer max) {
		return max != null && used > max;
	}

}
