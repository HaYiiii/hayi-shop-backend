package vn.hayi.shop.catalog.application;

import vn.hayi.shop.shared.patch.Change;

/** A Merge Patch of a Collection (Decision AL1); {@code status} is {@code ACTIVE} or {@code INACTIVE}. */
public record CollectionChanges(Change<String> name, Change<String> slug, Change<String> description,
		Change<Integer> sortOrder, Change<String> status) {
}
