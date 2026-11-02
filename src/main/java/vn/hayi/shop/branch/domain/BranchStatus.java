package vn.hayi.shop.branch.domain;

/** Branch lifecycle (Branch §4.3). {@code ACTIVE} is not open-now; {@code CLOSED} is terminal. */
public enum BranchStatus {

	DRAFT, ACTIVE, INACTIVE, CLOSED

}
