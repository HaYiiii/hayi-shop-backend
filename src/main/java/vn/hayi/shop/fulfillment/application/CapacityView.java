package vn.hayi.shop.fulfillment.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.stereotype.Service;

import vn.hayi.shop.fulfillment.api.PickupSlots;
import vn.hayi.shop.fulfillment.api.SlotDay;
import vn.hayi.shop.shared.time.BranchTime;

/** The staff capacity view of one day (API 09 §2.3, Decision V1), through the shared slot resolver. */
@Service
public class CapacityView {

	private final PickupSlots slots;

	private final Clock clock;

	CapacityView(PickupSlots slots, Clock clock) {
		this.slots = slots;
		this.clock = clock;
	}

	/** {@code date} defaults to today; only {@code PICKUP} in the MVP (otherwise 422, Decision R3). */
	public SlotDay capacityOn(UUID branchId, String fulfillmentType, LocalDate date) {
		FulfillmentConfigStore.supportedType(fulfillmentType);
		LocalDate day = (date != null) ? date : BranchTime.localDate(this.clock.instant(), BranchTime.DEFAULT_ZONE);
		return this.slots.capacityOn(branchId, day);
	}

}
