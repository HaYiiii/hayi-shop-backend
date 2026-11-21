package vn.hayi.shop.customer.api;

import java.util.UUID;

/**
 * Creates Customer profiles for Identity's registration (Identity §2.11, Customer §3.1): called synchronously in the
 * registration transaction, so there is never a registered Customer without a profile.
 */
public interface CustomerProfiles {

	boolean existsForUser(UUID userId);

	/**
	 * @return the new {@code customerId}
	 * @throws vn.hayi.shop.shared.error.DomainException {@code CUSTOMER_ALREADY_REGISTERED} when the user already has
	 * a profile
	 */
	UUID create(UUID userId, String displayName);

}
