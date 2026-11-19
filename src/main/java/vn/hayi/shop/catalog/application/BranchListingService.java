package vn.hayi.shop.catalog.application;

import java.time.Clock;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.audit.api.AuditEntry;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.audit.api.AuditSource;
import vn.hayi.shop.branch.api.BranchDirectory;
import vn.hayi.shop.catalog.domain.AvailabilityState;
import vn.hayi.shop.catalog.domain.BranchListingRepository;
import vn.hayi.shop.catalog.domain.BranchOptionItemListing;
import vn.hayi.shop.catalog.domain.BranchOptionItemListingRepository;
import vn.hayi.shop.catalog.domain.BranchProductListing;
import vn.hayi.shop.catalog.domain.CatalogErrorCode;
import vn.hayi.shop.catalog.domain.FieldDiff;
import vn.hayi.shop.catalog.domain.OptionGroup;
import vn.hayi.shop.catalog.domain.OptionGroupRepository;
import vn.hayi.shop.catalog.domain.OptionItem;
import vn.hayi.shop.catalog.domain.OptionStatus;
import vn.hayi.shop.catalog.domain.Product;
import vn.hayi.shop.catalog.domain.ProductRepository;
import vn.hayi.shop.catalog.domain.ProductStatus;
import vn.hayi.shop.catalog.domain.VariantStatus;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;

/**
 * What a Branch sells and at which Branch price (Catalog §5.5–5.6, API 08 §2, Decision F, W2, W3, AD). The Branch is
 * checked through {@code branch.api}; every change is audited with its Branch in the same transaction (W8).
 */
@Service
public class BranchListingService {

	private static final Sort BY_NAME = Sort.by("name", "id");

	private static final EnumSet<ProductStatus> LISTABLE = EnumSet.of(ProductStatus.DRAFT, ProductStatus.ACTIVE,
			ProductStatus.INACTIVE);

	private static final String LISTING_KEY = "uk_cat_branch_product_listing__branch_product";

	private final BranchListingRepository listings;

	private final BranchOptionItemListingRepository optionListings;

	private final ProductRepository products;

	private final OptionGroupRepository optionGroups;

	private final BranchDirectory branches;

	private final AuditRecorder audit;

	private final Clock clock;

	BranchListingService(BranchListingRepository listings, BranchOptionItemListingRepository optionListings,
			ProductRepository products, OptionGroupRepository optionGroups, BranchDirectory branches,
			AuditRecorder audit, Clock clock) {
		this.listings = listings;
		this.optionListings = optionListings;
		this.products = products;
		this.optionGroups = optionGroups;
		this.branches = branches;
		this.audit = audit;
		this.clock = clock;
	}

	/** Every non-archived Product, sorted by name, with its listing here or none (Decision AD6). */
	@Transactional(readOnly = true)
	public Page<BranchListingView> list(UUID branchId, String query, int page, int size) {
		this.branches.requireExists(branchId);
		Page<Product> found = this.products.search(LISTABLE, null, ProductService.pattern(query),
				PageRequest.of(page, size, BY_NAME));
		Map<UUID, BranchProductListing> byProduct = found.isEmpty() ? Map.of()
				: this.listings
					.findByBranchIdAndProductIdIn(branchId, found.getContent().stream().map(Product::getId).toList())
					.stream()
					.collect(Collectors.toMap(BranchProductListing::getProductId, Function.identity()));
		return found.map(product -> BranchListingView.of(product, byProduct.get(product.getId())));
	}

	/** The option items with a listing here, by {@code optionItemId} (Decision AM1). */
	@Transactional(readOnly = true)
	public List<BranchOptionItemListingView> listOptionItems(UUID branchId) {
		this.branches.requireExists(branchId);
		return this.optionListings.findByIdBranchId(branchId)
			.stream()
			.sorted(Comparator.comparing(BranchOptionItemListing::getOptionItemId))
			.map(BranchOptionItemListingView::of)
			.toList();
	}

	/**
	 * Puts a Product on sale here or takes it off, at {@code expected} version; {@code "v0"} creates the listing
	 * (Decision AD2). A concurrent first listing loses on the unique key with {@code VERSION_CONFLICT} (as R2).
	 */
	@Transactional
	public BranchListingView put(UUID branchId, UUID productId, String requestedState, ExpectedVersion expected,
			CatalogClient client) {
		AvailabilityState requested = AvailabilityState.valueOf(requestedState);
		this.branches.requireConfigurable(branchId);
		Product product = listable(productId);
		Optional<BranchProductListing> existing = this.listings.findByBranchIdAndProductId(branchId, productId);
		expected.verify(existing.map(BranchProductListing::getVersion).orElse(0L));
		BranchProductListing listing = existing
			.orElseGet(() -> BranchProductListing.create(branchId, productId, this.clock.instant()));
		AvailabilityState before = existing.isPresent() ? listing.getAvailabilityState() : null;
		FieldDiff diff = listing.setAvailability(requested, client.actor().actorUserId(), this.clock.instant());
		if (existing.isEmpty() || !diff.isEmpty()) {
			listing = save(listing);
			record(client, "BRANCH_LISTING_PUT", listing,
					(before != null) ? withProduct(productId, Map.of("availabilityState", before.name())) : null,
					withProduct(productId, Map.of("availabilityState", listing.getAvailabilityState().name())));
		}
		return BranchListingView.of(product, listing);
	}

	/** Takes a Product off sale here (Decision AD2, AD5); {@code expected} is {@code null} without {@code If-Match}. */
	@Transactional
	public BranchListingView unlist(UUID branchId, UUID productId, ExpectedVersion expected, CatalogClient client) {
		this.branches.requireConfigurable(branchId);
		Product product = listable(productId);
		BranchProductListing listing = existing(branchId, productId, expected);
		AvailabilityState before = listing.getAvailabilityState();
		FieldDiff diff = listing.setAvailability(AvailabilityState.NOT_AVAILABLE, client.actor().actorUserId(),
				this.clock.instant());
		if (!diff.isEmpty()) {
			listing = save(listing);
			record(client, "BRANCH_LISTING_UNLIST", listing,
					withProduct(productId, Map.of("availabilityState", before.name())),
					withProduct(productId, Map.of("availabilityState", listing.getAvailabilityState().name())));
		}
		return BranchListingView.of(product, listing);
	}

	/**
	 * Sets a Variant's Branch price and puts it on sale or takes it off, at the listing's {@code expected} version
	 * (Decision AD2, AD4).
	 */
	@Transactional
	public BranchListingView putVariant(UUID branchId, UUID productId, UUID variantId, Long price,
			String requestedState, ExpectedVersion expected, CatalogClient client) {
		AvailabilityState requested = AvailabilityState.valueOf(requestedState);
		this.branches.requireConfigurable(branchId);
		Product product = listable(productId);
		BranchProductListing listing = existing(branchId, productId, expected);
		product.findVariant(variantId)
			.filter(variant -> variant.getStatus() != VariantStatus.ARCHIVED)
			.orElseThrow(() -> new DomainException(CatalogErrorCode.CATALOG_VARIANT_NOT_FOUND,
					"No Variant " + variantId + " in Product " + productId));
		FieldDiff diff = listing.putVariant(variantId, price, requested, client.actor().actorUserId(),
				this.clock.instant());
		if (!diff.isEmpty()) {
			listing = save(listing);
			record(client, "BRANCH_VARIANT_LISTING_PUT", listing, withVariant(variantId, diff.oldValues()),
					withVariant(variantId, diff.newValues()));
		}
		return BranchListingView.of(product, listing);
	}

	/** Sets an option's Branch extra price at {@code expected} version; {@code "v0"} creates it (Decision AD3). */
	@Transactional
	public BranchOptionItemListingView putOptionItem(UUID branchId, UUID optionItemId, Long extraPrice,
			ExpectedVersion expected, CatalogClient client) {
		this.branches.requireConfigurable(branchId);
		OptionGroup group = this.optionGroups.findByItemId(optionItemId)
			.orElseThrow(() -> new DomainException(CatalogErrorCode.CATALOG_OPTION_ITEM_NOT_FOUND,
					"No option item " + optionItemId));
		OptionItem item = group.findItem(optionItemId).orElseThrow();
		if (group.getStatus() != OptionStatus.ACTIVE || item.getStatus() != OptionStatus.ACTIVE) {
			throw new DomainException(CatalogErrorCode.CATALOG_OPTION_ARCHIVED,
					"Option item " + optionItemId + " is archived");
		}
		BranchOptionItemListing.Key key = new BranchOptionItemListing.Key(branchId, optionItemId);
		Optional<BranchOptionItemListing> existing = this.optionListings.findById(key);
		expected.verify(existing.map(BranchOptionItemListing::getVersion).orElse(0L));
		BranchOptionItemListing listing = existing
			.orElseGet(() -> BranchOptionItemListing.create(branchId, optionItemId, this.clock.instant()));
		FieldDiff diff = listing.setExtraPrice(extraPrice, this.clock.instant());
		if (diff.isEmpty()) {
			// Nothing to store: a new row with no Branch price is the same as no row (version 0).
			return existing.isPresent() ? BranchOptionItemListingView.of(listing)
					: new BranchOptionItemListingView(optionItemId, null, AvailabilityState.AVAILABLE.name(), 0);
		}
		try {
			listing = this.optionListings.saveAndFlush(listing);
		}
		catch (DataIntegrityViolationException ex) {
			// Two first saves of one (branch, item): the later one loses on the primary key (as R2).
			String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
			if (message != null && message.contains("Duplicate entry")) {
				throw new DomainException(CommonErrorCode.VERSION_CONFLICT,
						"Concurrent first save of option item " + optionItemId + " at " + branchId);
			}
			throw ex;
		}
		Map<String, Object> oldValue = new LinkedHashMap<>();
		oldValue.put("optionItemId", optionItemId);
		oldValue.putAll(diff.oldValues());
		Map<String, Object> newValue = new LinkedHashMap<>();
		newValue.put("optionItemId", optionItemId);
		newValue.putAll(diff.newValues());
		this.audit.record(AuditEntry.of(client.actor(), AuditSource.BACKOFFICE_WEB, "BRANCH_OPTION_ITEM_LISTING_PUT",
				"BRANCH_OPTION_ITEM_LISTING", optionItemId)
			.withBranch(branchId)
			.withOldValue(oldValue)
			.withNewValue(newValue)
			.withClient(client.ipAddress(), client.userAgent()));
		return BranchOptionItemListingView.of(listing);
	}

	/** A Product a listing can be made for: it exists and is not archived (Decision W3). */
	private Product listable(UUID productId) {
		Product product = this.products.findById(productId)
			.orElseThrow(() -> new DomainException(CatalogErrorCode.CATALOG_PRODUCT_NOT_FOUND,
					"No Product " + productId));
		if (product.getStatus() == ProductStatus.ARCHIVED) {
			throw new DomainException(CatalogErrorCode.CATALOG_PRODUCT_ARCHIVED, "Product " + productId + " is archived");
		}
		return product;
	}

	private BranchProductListing existing(UUID branchId, UUID productId, ExpectedVersion expected) {
		BranchProductListing listing = this.listings.findByBranchIdAndProductId(branchId, productId)
			.orElseThrow(() -> new DomainException(CatalogErrorCode.CATALOG_LISTING_NOT_FOUND,
					"Product " + productId + " is not listed at Branch " + branchId));
		if (expected != null) {
			expected.verify(listing.getVersion());
		}
		return listing;
	}

	private BranchProductListing save(BranchProductListing listing) {
		try {
			return this.listings.saveAndFlush(listing);
		}
		catch (DataIntegrityViolationException ex) {
			String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
			if (message != null && message.contains(LISTING_KEY)) {
				throw new DomainException(CommonErrorCode.VERSION_CONFLICT, "Concurrent first listing of Product "
						+ listing.getProductId() + " at Branch " + listing.getBranchId());
			}
			throw ex;
		}
	}

	private void record(CatalogClient client, String action, BranchProductListing listing, Map<String, ?> oldValue,
			Map<String, ?> newValue) {
		this.audit.record(AuditEntry.of(client.actor(), AuditSource.BACKOFFICE_WEB, action, "BRANCH_PRODUCT_LISTING",
				listing.getId())
			.withBranch(listing.getBranchId())
			.withOldValue(oldValue)
			.withNewValue(newValue)
			.withClient(client.ipAddress(), client.userAgent()));
	}

	private static Map<String, Object> withProduct(UUID productId, Map<String, Object> values) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("productId", productId);
		entry.putAll(values);
		return entry;
	}

	private static Map<String, Object> withVariant(UUID variantId, Map<String, Object> values) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("variantId", variantId);
		entry.putAll(values);
		return entry;
	}

	/** An option's listing here; version {@code 0} while it has none. */
	public record BranchOptionItemListingView(UUID optionItemId, Long extraPrice, String availabilityState,
			long version) {

		static BranchOptionItemListingView of(BranchOptionItemListing listing) {
			return new BranchOptionItemListingView(listing.getOptionItemId(), listing.getExtraPrice(),
					listing.getAvailabilityState().name(), listing.getVersion());
		}

	}

}
