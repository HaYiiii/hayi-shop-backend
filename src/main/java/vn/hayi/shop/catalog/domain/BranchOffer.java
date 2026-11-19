package vn.hayi.shop.catalog.domain;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a customer may order of one Product at one Branch (Catalog §5.4–5.6, Decision F, AH, AI): its sizes with
 * Branch prices and its option groups with prices in three layers. Pure: the caller loads everything.
 */
public record BranchOffer(BranchSellability sellability, List<OfferedVariant> variants, List<OfferedGroup> groups) {

	/**
	 * Empty when the Product is not on the Branch's menu (AH1).
	 * @param groups the option groups attached to {@code product}, by id
	 * @param optionListings the Branch's option overrides, by option item id
	 */
	public static Optional<BranchOffer> of(Product product, BranchProductListing listing, Map<UUID, OptionGroup> groups,
			Map<UUID, BranchOptionItemListing> optionListings) {
		Map<UUID, AvailabilityState> optionStates = optionListings.values()
			.stream()
			.collect(Collectors.toMap(BranchOptionItemListing::getOptionItemId,
					BranchOptionItemListing::getAvailabilityState));
		return BranchSellability.of(product, listing, groups, optionStates)
			.map(sellability -> new BranchOffer(sellability,
					variants(product, listing, sellability.availability()), groups(product, groups, optionListings)));
	}

	/**
	 * Listed {@code ACTIVE} sizes; a stopped Product stops every size (AI2, Decision F), whether stopped by hand or
	 * derived (AH3, e.g. a required option group with nothing left).
	 */
	private static List<OfferedVariant> variants(Product product, BranchProductListing listing,
			AvailabilityState productState) {
		boolean productStopped = EnumSet.of(AvailabilityState.SOLD_OUT, AvailabilityState.PAUSED).contains(productState);
		return product.getVariants()
			.stream()
			.filter(variant -> variant.getStatus() == VariantStatus.ACTIVE)
			.sorted(Comparator.comparingInt(ProductVariant::getSortOrder))
			.map(variant -> {
				Optional<BranchVariantListing> override = listing.variant(variant.getId());
				AvailabilityState state = override.map(BranchVariantListing::availabilityState)
					.orElse(AvailabilityState.AVAILABLE);
				Long price = override.map(BranchVariantListing::price).orElse(null);
				return new OfferedVariant(variant.getId(), variant.getName(),
						(price != null) ? price : variant.getBasePrice(), variant.isDefault(),
						(productStopped && state != AvailabilityState.NOT_AVAILABLE) ? productState : state);
			})
			.filter(offered -> offered.availability != AvailabilityState.NOT_AVAILABLE)
			.toList();
	}

	/** {@code ACTIVE} groups in attach order with their visible {@code ACTIVE} items (AI3). */
	private static List<OfferedGroup> groups(Product product, Map<UUID, OptionGroup> groups,
			Map<UUID, BranchOptionItemListing> optionListings) {
		Map<UUID, ProductOptionItemOverride> overrides = product.getOptionItemOverrides()
			.stream()
			.collect(Collectors.toMap(ProductOptionItemOverride::optionItemId, Function.identity()));
		return product.getOptionGroups()
			.stream()
			.sorted(Comparator.comparingInt(ProductOptionGroupConfig::sortOrder))
			.map(config -> {
				OptionGroup group = groups.get(config.optionGroupId());
				if (group == null || group.getStatus() != OptionStatus.ACTIVE) {
					return null;
				}
				List<OfferedItem> items = group.getItems()
					.stream()
					.filter(item -> item.getStatus() == OptionStatus.ACTIVE)
					.filter(item -> !Optional.ofNullable(overrides.get(item.getId()))
						.map(ProductOptionItemOverride::isHidden)
						.orElse(false))
					.sorted(Comparator.comparingInt(OptionItem::getSortOrder))
					.map(item -> item(item, overrides.get(item.getId()), optionListings.get(item.getId())))
					.toList();
				return new OfferedGroup(group.getId(), group.getName(), group.getSelectionType().name(),
						config.effectiveMin(group), config.effectiveMax(group), items);
			})
			.filter(Objects::nonNull)
			.toList();
	}

	/** Price: Branch → Product override → library, the first layer that sets one (AI3, Catalog §5.5). */
	private static OfferedItem item(OptionItem item, ProductOptionItemOverride override,
			BranchOptionItemListing branch) {
		Long branchPrice = (branch != null) ? branch.getExtraPrice() : null;
		Long productPrice = (override != null) ? override.extraPriceOverride() : null;
		long price = (branchPrice != null) ? branchPrice
				: (productPrice != null) ? productPrice : item.getDefaultExtraPrice();
		AvailabilityState state = (branch != null) ? branch.getAvailabilityState() : AvailabilityState.AVAILABLE;
		return new OfferedItem(item.getId(), item.getName(), price, item.isDefaultSelected(),
				(state == AvailabilityState.NOT_AVAILABLE) ? AvailabilityState.SOLD_OUT : state);
	}

	public record OfferedVariant(UUID variantId, String name, long price, boolean isDefault,
			AvailabilityState availability) {
	}

	public record OfferedGroup(UUID optionGroupId, String name, String selectionType, int minSelect, int maxSelect,
			List<OfferedItem> items) {
	}

	/** {@code isDefaultSelected} stays as in the library even when the item is out: no silent substitute (F). */
	public record OfferedItem(UUID optionItemId, String name, long extraPrice, boolean isDefaultSelected,
			AvailabilityState availability) {
	}

}
