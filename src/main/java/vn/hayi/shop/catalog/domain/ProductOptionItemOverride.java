package vn.hayi.shop.catalog.domain;

import java.util.UUID;

import jakarta.persistence.Embeddable;

/**
 * Layer 2 of option pricing: this Product's price for an item ({@code null} = library price) and whether the item is
 * hidden here (Catalog §5.4, ERD 05 {@code cat_product_option_item_override}, Decision AC3).
 */
@Embeddable
public record ProductOptionItemOverride(UUID optionItemId, Long extraPriceOverride, boolean isHidden) {
}
