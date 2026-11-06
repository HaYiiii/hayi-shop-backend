package vn.hayi.shop.catalog.domain;

import java.util.UUID;

/** One entry of {@code PUT /products/{id}/option-groups} (Decision AC2); {@code null} override = the group's value. */
public record OptionGroupChoice(UUID optionGroupId, Integer minSelectOverride, Integer maxSelectOverride) {
}
