package vn.hayi.shop.catalog.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface BranchListingRepository extends Repository<BranchProductListing, UUID> {

	/** Inserts now, so a concurrent first listing fails here on its unique key. */
	BranchProductListing saveAndFlush(BranchProductListing listing);

	Optional<BranchProductListing> findByBranchIdAndProductId(UUID branchId, UUID productId);

	List<BranchProductListing> findByBranchIdAndProductIdIn(UUID branchId, Collection<UUID> productIds);

}
