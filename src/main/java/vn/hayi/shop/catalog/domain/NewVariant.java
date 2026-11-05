package vn.hayi.shop.catalog.domain;

/** A Variant to create; {@code basePrice} is the full selling price in VND (Catalog §5.2). */
public record NewVariant(String sku, String name, long basePrice, boolean isDefault) {
}
