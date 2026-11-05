package vn.hayi.shop.catalog.application;

import java.util.List;
import java.util.UUID;

/**
 * A Product to create (Decision Z1). {@code productType} is the API name ({@code STANDARD}, {@code COMBO}); the web
 * layer has already settled which Variant is the default (Z3).
 */
public record NewProduct(String productCode, String slug, String name, String productType, UUID primaryCategoryId,
		String shortDescription, String description, List<Variant> variants) {

	public record Variant(String sku, String name, long basePrice, boolean isDefault) {
	}

}
