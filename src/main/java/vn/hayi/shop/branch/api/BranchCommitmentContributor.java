package vn.hayi.shop.branch.api;

import java.util.List;
import java.util.UUID;

/**
 * Implemented by each module that owns commitments at a Branch (Order, Group Order…) so that deactivate/close can be
 * blocked while any is open (Branch §4.5, Decision P3). Branch never depends on those modules; it collects every
 * contributor bean. Called inside the lifecycle transaction, so implementations only read.
 */
public interface BranchCommitmentContributor {

	/** The open commitments at {@code branchId}, by type; empty or zero counts when there are none. */
	List<BranchCommitment> openCommitments(UUID branchId);

}
