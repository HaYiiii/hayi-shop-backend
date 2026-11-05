package vn.hayi.shop.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import vn.hayi.shop.catalog.domain.Category;

/** A Category as read; {@code children} is empty for a child and for the single-Category reads. */
public record CategoryView(UUID categoryId, String slug, String name, String description, UUID parentId,
		int sortOrder, String status, long version, Instant createdAt, Instant updatedAt, List<CategoryView> children) {

	static CategoryView of(Category category) {
		return of(category, List.of());
	}

	static CategoryView of(Category category, List<CategoryView> children) {
		return new CategoryView(category.getId(), category.getSlug(), category.getName(), category.getDescription(),
				category.getParentId(), category.getSortOrder(), category.getStatus().name(), category.getVersion(),
				category.getCreatedAt(), category.getUpdatedAt(), List.copyOf(children));
	}

}
