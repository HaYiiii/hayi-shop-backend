package vn.hayi.shop.branch.api;

/** Kinds of open commitment that block deactivating or closing a Branch (API 09 §1.3, Decision P3). */
public enum BranchCommitmentType {

	ACTIVE_ORDERS,
	OPEN_GROUP_SESSIONS,
	FUTURE_SCHEDULED_ORDERS

}
