package vn.hayi.shop.catalog.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.branch.api.BranchDirectory;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver;
import vn.hayi.shop.catalog.domain.AvailabilityState;
import vn.hayi.shop.catalog.domain.BranchListingRepository;
import vn.hayi.shop.catalog.domain.BranchOffer;
import vn.hayi.shop.catalog.domain.BranchOptionItemListing;
import vn.hayi.shop.catalog.domain.BranchOptionItemListingRepository;
import vn.hayi.shop.catalog.domain.BranchProductListing;
import vn.hayi.shop.catalog.domain.OptionGroup;
import vn.hayi.shop.catalog.domain.OptionGroupRepository;
import vn.hayi.shop.catalog.domain.Product;
import vn.hayi.shop.catalog.domain.ProductOptionGroupConfig;
import vn.hayi.shop.catalog.domain.ProductRepository;
import vn.hayi.shop.catalog.domain.SelectionResult;

/**
 * {@code catalog.api} resolver (Catalog §5.8, Decision AJ): loads the Branch's offer of every Product in the lines
 * once, resolves each line against it, and marks the whole result with a fingerprint.
 */
@Service
class CatalogSelectionResolverService implements CatalogSelectionResolver {

	private final BranchDirectory branches;

	private final BranchListingRepository listings;

	private final BranchOptionItemListingRepository optionListings;

	private final ProductRepository products;

	private final OptionGroupRepository optionGroups;

	CatalogSelectionResolverService(BranchDirectory branches, BranchListingRepository listings,
			BranchOptionItemListingRepository optionListings, ProductRepository products,
			OptionGroupRepository optionGroups) {
		this.branches = branches;
		this.listings = listings;
		this.optionListings = optionListings;
		this.products = products;
		this.optionGroups = optionGroups;
	}

	@Override
	@Transactional(readOnly = true)
	public ResolvedSelections resolve(UUID branchId, List<SelectionRequest> lines, Instant at) {
		this.branches.requireActive(branchId);
		Set<UUID> productIds = lines.stream().map(SelectionRequest::productId).collect(Collectors.toSet());
		Map<UUID, Product> byId = productIds.isEmpty() ? Map.of()
				: this.products.findByIdIn(productIds)
					.stream()
					.collect(Collectors.toMap(Product::getId, Function.identity()));
		Map<UUID, BranchProductListing> listingByProduct = productIds.isEmpty() ? Map.of()
				: this.listings.findByBranchIdAndProductIdIn(branchId, productIds)
					.stream()
					.collect(Collectors.toMap(BranchProductListing::getProductId, Function.identity()));
		Map<UUID, OptionGroup> groups = groupsOf(byId.values());
		Map<UUID, BranchOptionItemListing> options = new HashMap<>();
		this.optionListings.findByIdBranchId(branchId).forEach(option -> options.put(option.getOptionItemId(), option));
		Map<UUID, BranchOffer> offers = new LinkedHashMap<>();
		byId.values()
			.forEach(product -> BranchOffer.of(product, listingByProduct.get(product.getId()), groups, options)
				.ifPresent(offer -> offers.put(product.getId(), offer)));
		List<ResolvedLine> resolved = lines.stream().map(line -> {
			BranchOffer offer = offers.get(line.productId());
			SelectionResult result = (offer != null) ? offer.resolve(line.variantId(), line.optionItemIds())
					: SelectionResult.notOnMenu();
			return toLine(line, result);
		}).toList();
		return new ResolvedSelections(resolved, fingerprint(resolved));
	}

	private Map<UUID, OptionGroup> groupsOf(Collection<Product> found) {
		List<UUID> ids = found.stream()
			.flatMap(product -> product.getOptionGroups().stream())
			.map(ProductOptionGroupConfig::optionGroupId)
			.distinct()
			.toList();
		return ids.isEmpty() ? Map.of()
				: this.optionGroups.findByIdIn(ids)
					.stream()
					.collect(Collectors.toMap(OptionGroup::getId, Function.identity()));
	}

	private static ResolvedLine toLine(SelectionRequest line, SelectionResult result) {
		Long unitPrice = result.unitPrice();
		Map<UUID, Availability> options = new LinkedHashMap<>();
		result.normalizedOptionItemIds()
			.forEach(id -> options.put(id, availability(result.optionAvailability().get(id))));
		return new ResolvedLine(line.ref(), result.valid(), line.productId(), line.variantId(),
				result.normalizedOptionItemIds(), unitPrice, (unitPrice != null) ? unitPrice * line.quantity() : null,
				availability(result.productAvailability()), availability(result.variantAvailability()), options,
				result.issues()
					.stream()
					.map(issue -> new Issue(IssueCode.valueOf(issue.code().name()), issue.optionItemId(),
							issue.optionGroupId()))
					.toList());
	}

	private static Availability availability(AvailabilityState state) {
		return (state != null) ? Availability.valueOf(state.name()) : null;
	}

	/** An opaque SHA-256 of everything a client shows: prices, states and issues of every line (AJ4). */
	private static String fingerprint(List<ResolvedLine> lines) {
		StringBuilder canonical = new StringBuilder();
		for (ResolvedLine line : lines) {
			canonical.append(line.ref()).append('|').append(line.productId()).append('|').append(line.variantId())
				.append('|').append(line.normalizedOptionItemIds()).append('|').append(line.unitPrice())
				.append('|').append(line.lineTotal()).append('|').append(line.productAvailability())
				.append('|').append(line.variantAvailability()).append('|').append(line.optionAvailability())
				.append('|').append(line.issues()).append('\n');
		}
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is always available", ex);
		}
	}

}
