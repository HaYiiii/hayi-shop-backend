package vn.hayi.shop.customer.domain;

import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface CustomerProfileRepository extends Repository<CustomerProfile, UUID> {

	/** Inserts now, so a concurrent registration of the same user fails on {@code uk_cus_customer_profile__user_id}. */
	CustomerProfile saveAndFlush(CustomerProfile profile);

	boolean existsByUserId(UUID userId);

}
