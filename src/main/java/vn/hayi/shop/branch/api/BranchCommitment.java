package vn.hayi.shop.branch.api;

import java.util.Objects;

/** {@code count} open commitments of one {@code type} at a Branch; a count of zero is not a conflict. */
public record BranchCommitment(BranchCommitmentType type, long count) {

	public BranchCommitment {
		Objects.requireNonNull(type, "type");
		if (count < 0) {
			throw new IllegalArgumentException("count must not be negative");
		}
	}

}
