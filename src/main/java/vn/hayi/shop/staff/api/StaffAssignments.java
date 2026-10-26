package vn.hayi.shop.staff.api;

import java.util.Set;
import java.util.UUID;

/** Where a user works as staff today (Staff Operations §16, Decision A); read by Identity for effective permission. */
public interface StaffAssignments {

	/**
	 * Branches where the user's StaffProfile has an {@code ACTIVE} assignment whose range contains today
	 * ({@code Asia/Ho_Chi_Minh}); empty for a user without StaffProfile or whose StaffProfile is {@code INACTIVE}
	 * (Staff §16.12).
	 */
	Set<UUID> activeBranchIds(UUID userId);

}
