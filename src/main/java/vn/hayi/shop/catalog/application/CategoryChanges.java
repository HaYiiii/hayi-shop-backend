package vn.hayi.shop.catalog.application;

import java.util.UUID;

import vn.hayi.shop.shared.patch.Change;

/** A Merge Patch of a Category (Decision Y3, AG4); {@code imageObjectId} {@code null} clears the image. */
public record CategoryChanges(Change<String> name, Change<String> slug, Change<String> description,
		Change<UUID> parentId, Change<UUID> imageObjectId) {
}
