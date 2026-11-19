package vn.hayi.shop.catalog.application;

import java.time.Instant;
import java.util.UUID;

import vn.hayi.shop.catalog.domain.Tag;

/** A Tag as read in the Backoffice (Decision AL2). */
public record TagView(UUID tagId, String slug, String name, Instant createdAt) {

	static TagView of(Tag tag) {
		return new TagView(tag.getId(), tag.getSlug(), tag.getName(), tag.getCreatedAt());
	}

}
