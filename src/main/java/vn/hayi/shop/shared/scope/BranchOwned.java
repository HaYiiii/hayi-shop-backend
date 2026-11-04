package vn.hayi.shop.shared.scope;

import java.util.UUID;

/**
 * An aggregate root that belongs to one Branch (API README §3.1, Decision S7). Its repository has no bare id lookup
 * ({@code findById}, {@code existsById}, {@code getById}, {@code deleteById}): it is always found by
 * {@code (branchId, …)}, and its child entities only through it, so an id from another Branch on the path is simply
 * not found — the same {@code 404} as an id that does not exist. {@code ArchitectureTest} enforces the repository
 * side.
 */
public interface BranchOwned {

	UUID getBranchId();

}
