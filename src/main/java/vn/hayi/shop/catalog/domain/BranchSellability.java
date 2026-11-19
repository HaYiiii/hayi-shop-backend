package vn.hayi.shop.catalog.domain;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Whether and how a Product is offered at one Branch, as customers see it (Catalog §5.6, Decision F, AH1–AH3). Pure:
 * the caller loads the Product, its listing there, its attached groups and the Branch's option overrides.
 * @param availability {@code AVAILABLE}, {@code SOLD_OUT} or {@code PAUSED}
 * @param fromPrice the lowest effective price of the sizes on offer, preferring the {@code AVAILABLE} ones
 */
public record BranchSellability(AvailabilityState availability, long fromPrice) {

	private static final Set<AvailabilityState> STOPPED = EnumSet.of(AvailabilityState.SOLD_OUT,
			AvailabilityState.PAUSED);

	/**
	 * Empty when the Product is not on the menu: not {@code ACTIVE}, no listing or off sale, or every {@code ACTIVE}
	 * Variant off sale at the Branch (AH1).
	 * @param groups the option groups attached to {@code product}, by id
	 * @param optionStates the Branch's state of each option item that has an override there
	 */
	public static Optional<BranchSellability> of(Product product, BranchProductListing listing,
			Map<UUID, OptionGroup> groups, Map<UUID, AvailabilityState> optionStates) {
		if (product.getStatus() != ProductStatus.ACTIVE || listing == null
				|| listing.getAvailabilityState() == AvailabilityState.NOT_AVAILABLE) {
			return Optional.empty();
		}
		List<Offered> offered = product.getVariants()
			.stream()
			.filter(variant -> variant.getStatus() == VariantStatus.ACTIVE)
			.map(variant -> {
				Optional<BranchVariantListing> override = listing.variant(variant.getId());
				AvailabilityState state = override.map(BranchVariantListing::availabilityState)
					.orElse(AvailabilityState.AVAILABLE);
				Long price = override.map(BranchVariantListing::price).orElse(null);
				return new Offered(state, (price != null) ? price : variant.getBasePrice());
			})
			.filter(size -> size.state != AvailabilityState.NOT_AVAILABLE)
			.toList();
		if (offered.isEmpty()) {
			return Optional.empty();
		}
		List<Offered> available = offered.stream().filter(size -> size.state == AvailabilityState.AVAILABLE).toList();
		long fromPrice = (available.isEmpty() ? offered : available).stream()
			.mapToLong(Offered::price)
			.min()
			.orElseThrow();
		return Optional.of(new BranchSellability(availability(product, listing, available, groups, optionStates),
				fromPrice));
	}

	private static AvailabilityState availability(Product product, BranchProductListing listing,
			List<Offered> available, Map<UUID, OptionGroup> groups, Map<UUID, AvailabilityState> optionStates) {
		if (STOPPED.contains(listing.getAvailabilityState())) {
			return listing.getAvailabilityState();
		}
		if (available.isEmpty()) {
			return AvailabilityState.SOLD_OUT;
		}
		Set<UUID> hidden = product.getOptionItemOverrides()
			.stream()
			.filter(ProductOptionItemOverride::isHidden)
			.map(ProductOptionItemOverride::optionItemId)
			.collect(Collectors.toSet());
		for (ProductOptionGroupConfig config : product.getOptionGroups()) {
			OptionGroup group = groups.get(config.optionGroupId());
			if (group != null && !requiredGroupSatisfied(config, group, hidden, optionStates)) {
				return AvailabilityState.SOLD_OUT;
			}
		}
		return AvailabilityState.AVAILABLE;
	}

	/** An optional group never blocks; a required one needs enough sellable items, and is unsatisfied once archived. */
	static boolean requiredGroupSatisfied(ProductOptionGroupConfig config, OptionGroup group, Set<UUID> hidden,
			Map<UUID, AvailabilityState> optionStates) {
		int min = config.effectiveMin(group);
		if (min <= 0) {
			return true;
		}
		if (group.getStatus() != OptionStatus.ACTIVE) {
			return false;
		}
		long sellable = group.getItems()
			.stream()
			.filter(item -> item.getStatus() == OptionStatus.ACTIVE && !hidden.contains(item.getId()))
			.filter(item -> optionStates.getOrDefault(item.getId(), AvailabilityState.AVAILABLE)
					== AvailabilityState.AVAILABLE)
			.count();
		return sellable >= min;
	}

	private record Offered(AvailabilityState state, long price) {
	}

}
