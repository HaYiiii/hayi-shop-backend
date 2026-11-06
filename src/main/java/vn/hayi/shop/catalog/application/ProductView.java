package vn.hayi.shop.catalog.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import vn.hayi.shop.catalog.domain.OptionGroup;
import vn.hayi.shop.catalog.domain.Product;
import vn.hayi.shop.catalog.domain.ProductOptionGroupConfig;
import vn.hayi.shop.catalog.domain.ProductOptionItemOverride;
import vn.hayi.shop.catalog.domain.ProductVariant;

/** A Product as read in the Backoffice, with every Variant and its option configuration (Decision Z7, AC5). */
public record ProductView(UUID productId, String productCode, String slug, String name, String productType,
		String status, UUID primaryCategoryId, String shortDescription, String description, int sortOrder,
		Instant archivedAt, Instant createdAt, Instant updatedAt, long version, List<Variant> variants,
		List<AttachedGroup> optionGroups, List<ItemOverride> optionItemOverrides) {

	/** {@code groups} are the option groups attached to {@code product}, by id. */
	static ProductView of(Product product, Map<UUID, OptionGroup> groups) {
		return new ProductView(product.getId(), product.getProductCode(), product.getSlug(), product.getName(),
				product.getProductType().name(), product.getStatus().name(), product.getPrimaryCategoryId(),
				product.getShortDescription(), product.getDescription(), product.getSortOrder(),
				product.getArchivedAt(), product.getCreatedAt(), product.getUpdatedAt(), product.getVersion(),
				product.getVariants().stream().map(Variant::of).toList(),
				product.getOptionGroups().stream()
					.map(config -> AttachedGroup.of(config, groups.get(config.optionGroupId())))
					.toList(),
				product.getOptionItemOverrides()
					.stream()
					.sorted(Comparator.comparing(ProductOptionItemOverride::optionItemId))
					.map(ItemOverride::of)
					.toList());
	}

	public record Variant(UUID variantId, String sku, String name, long basePrice, boolean isDefault, String status,
			int sortOrder) {

		static Variant of(ProductVariant variant) {
			return new Variant(variant.getId(), variant.getSku(), variant.getName(), variant.getBasePrice(),
					variant.isDefault(), variant.getStatus().name(), variant.getSortOrder());
		}

	}

	/** An attached group with its effective min/max: the override, else the group's own (Decision AC5). */
	public record AttachedGroup(UUID optionGroupId, String code, String name, String selectionType, int minSelect,
			int maxSelect, Integer minSelectOverride, Integer maxSelectOverride, int sortOrder) {

		static AttachedGroup of(ProductOptionGroupConfig config, OptionGroup group) {
			return new AttachedGroup(config.optionGroupId(), group.getCode(), group.getName(),
					group.getSelectionType().name(),
					(config.minSelectOverride() != null) ? config.minSelectOverride() : group.getMinSelect(),
					(config.maxSelectOverride() != null) ? config.maxSelectOverride() : group.getMaxSelect(),
					config.minSelectOverride(), config.maxSelectOverride(), config.sortOrder());
		}

	}

	public record ItemOverride(UUID optionItemId, Long extraPriceOverride, boolean isHidden) {

		static ItemOverride of(ProductOptionItemOverride override) {
			return new ItemOverride(override.optionItemId(), override.extraPriceOverride(), override.isHidden());
		}

	}

}
