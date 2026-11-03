package vn.hayi.shop.branch.api;

import java.util.UUID;

/**
 * Lets other modules check a Branch before they store anything for it, without reading Branch tables (Decision R4).
 * Refusals are the Branch module's own Problem Details codes. Implemented by the Branch module.
 */
public interface BranchDirectory {

	/** @throws vn.hayi.shop.shared.error.DomainException {@code BRANCH_NOT_FOUND} */
	void requireExists(UUID branchId);

	/**
	 * The Branch exists and may still be configured: anything but {@code CLOSED} (Decision O2, R4).
	 * @throws vn.hayi.shop.shared.error.DomainException {@code BRANCH_NOT_FOUND}, or
	 * {@code BRANCH_INVALID_TRANSITION} once {@code CLOSED}
	 */
	void requireConfigurable(UUID branchId);

}
