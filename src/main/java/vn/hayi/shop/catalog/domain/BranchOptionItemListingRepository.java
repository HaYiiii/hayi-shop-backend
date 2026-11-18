package vn.hayi.shop.catalog.domain;

import java.util.Optional;

import org.springframework.data.repository.Repository;

public interface BranchOptionItemListingRepository
		extends Repository<BranchOptionItemListing, BranchOptionItemListing.Key> {

	/** Inserts or updates now, so a concurrent first save fails here on the primary key. */
	BranchOptionItemListing saveAndFlush(BranchOptionItemListing listing);

	Optional<BranchOptionItemListing> findById(BranchOptionItemListing.Key id);

}
