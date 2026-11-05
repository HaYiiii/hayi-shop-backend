package vn.hayi.shop.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import vn.hayi.shop.catalog.domain.Product;
import vn.hayi.shop.catalog.domain.ProductVariant;

/** A Product as read in the Backoffice, with every Variant (Decision Z7). */
public record ProductView(UUID productId, String productCode, String slug, String name, String productType,
		String status, UUID primaryCategoryId, String shortDescription, String description, int sortOrder,
		Instant archivedAt, Instant createdAt, Instant updatedAt, long version, List<Variant> variants) {

	static ProductView of(Product product) {
		return new ProductView(product.getId(), product.getProductCode(), product.getSlug(), product.getName(),
				product.getProductType().name(), product.getStatus().name(), product.getPrimaryCategoryId(),
				product.getShortDescription(), product.getDescription(), product.getSortOrder(),
				product.getArchivedAt(), product.getCreatedAt(), product.getUpdatedAt(), product.getVersion(),
				product.getVariants().stream().map(Variant::of).toList());
	}

	public record Variant(UUID variantId, String sku, String name, long basePrice, boolean isDefault, String status,
			int sortOrder) {

		static Variant of(ProductVariant variant) {
			return new Variant(variant.getId(), variant.getSku(), variant.getName(), variant.getBasePrice(),
					variant.isDefault(), variant.getStatus().name(), variant.getSortOrder());
		}

	}

}
