package vn.hayi.shop.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import vn.hayi.shop.catalog.domain.Collection;

/** A Collection as read in the Backoffice, its Products in order (Decision AL1). */
public record CollectionView(UUID collectionId, String slug, String name, String description, String status,
		int sortOrder, List<UUID> productIds, long version, Instant createdAt, Instant updatedAt) {

	static CollectionView of(Collection collection) {
		return new CollectionView(collection.getId(), collection.getSlug(), collection.getName(),
				collection.getDescription(), collection.getStatus().name(), collection.getSortOrder(),
				collection.getProductIds(), collection.getVersion(), collection.getCreatedAt(),
				collection.getUpdatedAt());
	}

}
