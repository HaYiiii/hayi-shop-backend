package vn.hayi.shop.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import vn.hayi.shop.catalog.domain.Category;

/**
 * A Category as read; {@code children} is empty for a child and for the single-Category reads. {@code imageUrl} is
 * the public URL of its image, if any (Decision AG5).
 */
public record CategoryView(UUID categoryId, String slug, String name, String description, UUID parentId,
		int sortOrder, String status, long version, Instant createdAt, Instant updatedAt, UUID imageObjectId,
		String imageUrl, List<CategoryView> children) {

	static CategoryView of(Category category, String imageUrl) {
		return of(category, imageUrl, List.of());
	}

	static CategoryView of(Category category, String imageUrl, List<CategoryView> children) {
		return new CategoryView(category.getId(), category.getSlug(), category.getName(), category.getDescription(),
				category.getParentId(), category.getSortOrder(), category.getStatus().name(), category.getVersion(),
				category.getCreatedAt(), category.getUpdatedAt(), category.getImageObjectId(), imageUrl,
				List.copyOf(children));
	}

}
