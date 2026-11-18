package vn.hayi.shop.catalog.application;

import java.util.List;
import java.util.UUID;

import vn.hayi.shop.catalog.domain.AvailabilityState;
import vn.hayi.shop.catalog.domain.BranchProductListing;
import vn.hayi.shop.catalog.domain.BranchVariantListing;
import vn.hayi.shop.catalog.domain.Product;

/**
 * A non-archived Product with its listing at one Branch, {@code null} while not sold there (Decision AD6).
 */
public record BranchListingView(UUID productId, String productCode, String name, String status, Listing listing) {

	static BranchListingView of(Product product, BranchProductListing listing) {
		return new BranchListingView(product.getId(), product.getProductCode(), product.getName(),
				product.getStatus().name(), (listing != null) ? Listing.of(product, listing) : null);
	}

	public record Listing(String availabilityState, long version, List<VariantRow> variants) {

		static Listing of(Product product, BranchProductListing listing) {
			return new Listing(listing.getAvailabilityState().name(), listing.getVersion(),
					product.liveVariants().stream().map(variant -> {
						BranchVariantListing override = listing.variant(variant.getId()).orElse(null);
						Long price = (override != null) ? override.price() : null;
						AvailabilityState state = (override != null) ? override.availabilityState()
								: AvailabilityState.AVAILABLE;
						return new VariantRow(variant.getId(), variant.getName(), variant.getBasePrice(), price,
								(price != null) ? price : variant.getBasePrice(), state.name());
					}).toList());
		}

	}

	/** {@code effectivePrice} = Branch price, or base price when there is none (Catalog §5.5). */
	public record VariantRow(UUID variantId, String name, long basePrice, Long price, long effectivePrice,
			String availabilityState) {
	}

}
