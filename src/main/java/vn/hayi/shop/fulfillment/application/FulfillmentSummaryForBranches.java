package vn.hayi.shop.fulfillment.application;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.branch.api.BranchFulfillmentSummary;
import vn.hayi.shop.fulfillment.api.PickupSlots;
import vn.hayi.shop.fulfillment.api.SlotQuery;
import vn.hayi.shop.fulfillment.api.SlotStatus;
import vn.hayi.shop.fulfillment.domain.BranchFulfillmentConfig;
import vn.hayi.shop.fulfillment.domain.FulfillmentType;
import vn.hayi.shop.shared.time.BranchTime;

/**
 * Answers Branch's {@link BranchFulfillmentSummary} port from the configs and the slot resolver (Decision N5, U4):
 * the earliest slot is the first {@code AVAILABLE}/{@code NEARLY_FULL} one with the resolver's default horizon.
 */
@Service
class FulfillmentSummaryForBranches implements BranchFulfillmentSummary {

	private static final Set<SlotStatus> BOOKABLE = EnumSet.of(SlotStatus.AVAILABLE, SlotStatus.NEARLY_FULL);

	private final FulfillmentConfigStore configs;

	private final PickupSlots slots;

	FulfillmentSummaryForBranches(FulfillmentConfigStore configs, PickupSlots slots) {
		this.configs = configs;
		this.slots = slots;
	}

	@Override
	@Transactional(readOnly = true)
	public List<String> fulfillmentTypes(UUID branchId) {
		boolean pickup = this.configs.find(branchId, FulfillmentType.PICKUP)
			.map(BranchFulfillmentConfig::settings)
			.map(settings -> settings.enabled())
			.orElse(false);
		return pickup ? List.of(FulfillmentType.PICKUP.name()) : List.of();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Instant> earliestPickupAt(UUID branchId) {
		return this.slots.resolve(new SlotQuery(branchId, null, null, null, null)).days().stream()
			.flatMap(day -> day.slots().stream()
				.filter(slot -> BOOKABLE.contains(slot.status()))
				.map(slot -> BranchTime.toInstant(day.date(), slot.start(), BranchTime.DEFAULT_ZONE)))
			.findFirst();
	}

}
