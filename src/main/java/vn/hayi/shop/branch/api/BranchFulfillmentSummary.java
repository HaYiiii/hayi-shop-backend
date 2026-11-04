package vn.hayi.shop.branch.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the public Branch view shows about ordering at a Branch (Decision N5, U4). Declared by Branch, implemented by
 * Fulfillment, so Branch never depends on Fulfillment.
 */
public interface BranchFulfillmentSummary {

	/** Fulfillment types the Branch has enabled, e.g. {@code ["PICKUP"]}; empty when none. */
	List<String> fulfillmentTypes(UUID branchId);

	/** Start of the earliest slot a customer can still book with the default horizon; empty if there is none. */
	Optional<Instant> earliestPickupAt(UUID branchId);

}
