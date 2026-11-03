package vn.hayi.shop.branch.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import vn.hayi.shop.branch.api.BranchCommitment;
import vn.hayi.shop.branch.domain.BranchErrorCode;
import vn.hayi.shop.shared.error.DomainException;

/** Deactivate/close refused while commitments are open → {@code 409} with {@code conflicts[]} (API 09 §1). */
public class BranchOpenCommitmentsException extends DomainException {

	private final List<BranchCommitment> conflicts;

	BranchOpenCommitmentsException(UUID branchId, List<BranchCommitment> conflicts) {
		super(BranchErrorCode.BRANCH_HAS_OPEN_COMMITMENTS, "Branch " + branchId + " has open commitments " + conflicts);
		this.conflicts = List.copyOf(conflicts);
	}

	public List<BranchCommitment> conflicts() {
		return this.conflicts;
	}

	@Override
	public Map<String, Object> problemProperties() {
		return Map.of("conflicts", this.conflicts);
	}

}
