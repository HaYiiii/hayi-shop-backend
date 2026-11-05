package vn.hayi.shop.catalog.application;

import java.time.Instant;
import java.util.UUID;

import vn.hayi.shop.catalog.domain.Product;

/** One row of the Product list (Decision Z6); {@code fromPrice} is {@code null} without a live Variant. */
public record ProductSummary(UUID productId, String productCode, String slug, String name, String productType,
		String status, UUID primaryCategoryId, Long fromPrice, int variantCount, Instant updatedAt, long version) {

	static ProductSummary of(Product product) {
		var fromPrice = product.fromPrice();
		return new ProductSummary(product.getId(), product.getProductCode(), product.getSlug(), product.getName(),
				product.getProductType().name(), product.getStatus().name(), product.getPrimaryCategoryId(),
				fromPrice.isPresent() ? fromPrice.getAsLong() : null, product.liveVariants().size(),
				product.getUpdatedAt(), product.getVersion());
	}

}
