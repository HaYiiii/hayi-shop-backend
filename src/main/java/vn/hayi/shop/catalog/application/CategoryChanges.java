package vn.hayi.shop.catalog.application;

import java.util.UUID;

import vn.hayi.shop.shared.patch.Change;

/** A Merge Patch of a Category (Decision Y3); {@code parentId} set to {@code null} makes it a root. */
public record CategoryChanges(Change<String> name, Change<String> slug, Change<String> description,
		Change<UUID> parentId) {
}
