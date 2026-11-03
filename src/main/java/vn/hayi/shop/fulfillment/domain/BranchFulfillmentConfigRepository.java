package vn.hayi.shop.fulfillment.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface BranchFulfillmentConfigRepository extends Repository<BranchFulfillmentConfig, UUID> {

	/** Writes now, so a concurrent first save fails here on {@code uk_ful_branch_fulfillment_config__branch_type}. */
	BranchFulfillmentConfig saveAndFlush(BranchFulfillmentConfig config);

	Optional<BranchFulfillmentConfig> findByBranchIdAndFulfillmentType(UUID branchId, FulfillmentType type);

}
