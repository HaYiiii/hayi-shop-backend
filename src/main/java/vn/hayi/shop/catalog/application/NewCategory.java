package vn.hayi.shop.catalog.application;

import java.util.UUID;

/** {@code parentId} {@code null} creates a root. */
public record NewCategory(String slug, String name, String description, UUID parentId) {
}
