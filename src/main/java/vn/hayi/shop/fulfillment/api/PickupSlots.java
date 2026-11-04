package vn.hayi.shop.fulfillment.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Resolves a Branch's PICKUP slots (Fulfillment §8.3–8.5, Decision N1–N4, T), for the public slot view and for Create
 * Order (S5). Implemented by the Fulfillment module.
 */
public interface PickupSlots {

	/**
	 * @throws vn.hayi.shop.shared.error.DomainException {@code BRANCH_NOT_FOUND} for an unknown or not {@code ACTIVE}
	 * Branch; {@code VALIDATION_FAILED} with {@code errors[]} when the dates are outside the booking horizon (T1)
	 */
	SlotCalendar resolve(SlotQuery query);

	/**
	 * The PICKUP slots of one date with their real numbers, for staff (Decision V1): the same generation and statuses
	 * as {@link #resolve}, but for any date and any existing Branch, enabled or not.
	 * @throws vn.hayi.shop.shared.error.DomainException {@code BRANCH_NOT_FOUND} for an unknown Branch
	 */
	SlotDay capacityOn(UUID branchId, LocalDate date);

}
