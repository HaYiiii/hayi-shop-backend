package vn.hayi.shop.catalog.application;

import java.text.Collator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.branch.api.BranchDirectory;
import vn.hayi.shop.catalog.domain.AvailabilityState;
import vn.hayi.shop.catalog.domain.BranchListingRepository;
import vn.hayi.shop.catalog.domain.BranchOffer;
import vn.hayi.shop.catalog.domain.BranchOptionItemListing;
import vn.hayi.shop.catalog.domain.BranchOptionItemListingRepository;
import vn.hayi.shop.catalog.domain.CatalogErrorCode;
import vn.hayi.shop.catalog.domain.BranchProductListing;
import vn.hayi.shop.catalog.domain.BranchSellability;
import vn.hayi.shop.catalog.domain.Category;
import vn.hayi.shop.catalog.domain.CategoryRepository;
import vn.hayi.shop.catalog.domain.CategoryStatus;
import vn.hayi.shop.catalog.domain.CollectionRepository;
import vn.hayi.shop.catalog.domain.CollectionStatus;
import vn.hayi.shop.catalog.domain.TagRepository;
import vn.hayi.shop.catalog.domain.OptionGroup;
import vn.hayi.shop.catalog.domain.OptionGroupRepository;
import vn.hayi.shop.catalog.domain.Product;
import vn.hayi.shop.catalog.domain.ProductImage;
import vn.hayi.shop.catalog.domain.ProductOptionGroupConfig;
import vn.hayi.shop.catalog.domain.ProductRepository;
import vn.hayi.shop.catalog.domain.ProductStatus;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.time.BranchTime;

/**
 * The menu customers see at one Branch (API 02 §2, Catalog §5.6, Decision F, W3, W6, AH): read on each request
 * from the Products, their listings there, the option overrides and the Category tree.
 */
@Service
public class PublicMenuService {

	/** Vietnamese order of names, so accented names sit with their base letter rather than after Z. */
	private static final Collator VIETNAMESE = Collator.getInstance(Locale.forLanguageTag("vi"));

	private static final Comparator<Product> MENU_ORDER = Comparator.comparingInt(Product::getSortOrder)
		.thenComparing(Product::getName, VIETNAMESE)
		.thenComparing(Product::getId);

	private final BranchDirectory branches;

	private final BranchListingRepository listings;

	private final BranchOptionItemListingRepository optionListings;

	private final ProductRepository products;

	private final OptionGroupRepository optionGroups;

	private final CategoryRepository categories;

	private final CatalogImages images;

	private final Clock clock;

	private final TagRepository tags;

	private final CollectionRepository collections;

	PublicMenuService(BranchDirectory branches, BranchListingRepository listings,
			BranchOptionItemListingRepository optionListings, ProductRepository products,
			OptionGroupRepository optionGroups, CategoryRepository categories, CatalogImages images, Clock clock,
			TagRepository tags, CollectionRepository collections) {
		this.tags = tags;
		this.collections = collections;
		this.branches = branches;
		this.listings = listings;
		this.optionListings = optionListings;
		this.products = products;
		this.optionGroups = optionGroups;
		this.categories = categories;
		this.images = images;
		this.clock = clock;
	}

	/**
	 * The menu at {@code at} ({@code null} = now). Selling hours use {@code at} from BE #70; until then it is only
	 * echoed (Decision AH4).
	 * @throws vn.hayi.shop.shared.error.DomainException {@code BRANCH_NOT_FOUND} unless the Branch is {@code ACTIVE}
	 */
	@Transactional(readOnly = true)
	public Menu menu(UUID branchId, Instant at) {
		this.branches.requireActive(branchId);
		Instant evaluatedAt = (at != null) ? at : this.clock.instant();
		Map<UUID, BranchProductListing> byProduct = this.listings
			.findByBranchIdAndAvailabilityStateNot(branchId, AvailabilityState.NOT_AVAILABLE)
			.stream()
			.collect(Collectors.toMap(BranchProductListing::getProductId, Function.identity()));
		List<Product> found = byProduct.isEmpty() ? List.of()
				: this.products.findByIdInAndStatus(byProduct.keySet(), ProductStatus.ACTIVE);
		Map<UUID, OptionGroup> groups = groupsOf(found);
		Map<UUID, AvailabilityState> optionStates = new HashMap<>();
		this.optionListings.findByIdBranchId(branchId)
			.forEach(option -> optionStates.put(option.getOptionItemId(), option.getAvailabilityState()));
		Map<UUID, String> imageUrls = this.images.urls(found.stream()
			.map(PublicMenuService::primaryImage)
			.flatMap(Optional::stream)
			.toList());
		Map<UUID, MenuTag> tagSlugs = new HashMap<>();
		Set<UUID> tagIds = found.stream().flatMap(product -> product.getTagIds().stream()).collect(Collectors.toSet());
		if (!tagIds.isEmpty()) {
			this.tags.findByIdIn(tagIds).forEach(tag -> tagSlugs.put(tag.getId(), new MenuTag(tag.getSlug(), tag.getName())));
		}
		Map<UUID, List<MenuProduct>> byCategory = new LinkedHashMap<>();
		found.stream().sorted(MENU_ORDER).forEach(product -> BranchSellability
			.of(product, byProduct.get(product.getId()), groups, optionStates, local(evaluatedAt))
			.ifPresent(sellability -> byCategory.computeIfAbsent(product.getPrimaryCategoryId(), id -> new ArrayList<>())
				.add(new MenuProduct(product.getId(), product.getSlug(), product.getName(),
						primaryImage(product).map(imageUrls::get).orElse(null), sellability.fromPrice(),
						sellability.availability().name(), slugs(product, tagSlugs)))));
		List<MenuCategory> tree = tree(byCategory);
		// A Product shows only when its primary Category does (AH1), so read what the tree kept.
		Set<UUID> shown = new HashSet<>();
		tree.forEach(root -> collectShown(root, shown));
		return new Menu(branchId, evaluatedAt, tree, collections(shown));
	}

	private static void collectShown(MenuCategory category, Set<UUID> shown) {
		category.products().forEach(product -> shown.add(product.productId()));
		category.children().forEach(child -> collectShown(child, shown));
	}

	/** Its Tags by slug (AL3, amended 2026-11-20 to carry the name). */
	private static List<MenuTag> slugs(Product product, Map<UUID, MenuTag> tags) {
		return product.getTagIds()
			.stream()
			.map(tags::get)
			.filter(Objects::nonNull)
			.sorted(Comparator.comparing(MenuTag::slug))
			.toList();
	}

	/** {@code ACTIVE} Collections with the Products shown in this menu, in Collection order; empty ones dropped (AL3). */
	private List<MenuCollection> collections(Set<UUID> shown) {
		return this.collections.findByStatus(CollectionStatus.ACTIVE)
			.stream()
			.sorted(CollectionService.ORDER)
			.map(collection -> new MenuCollection(collection.getId(), collection.getName(),
					collection.getProductIds().stream().filter(shown::contains).toList()))
			.filter(collection -> !collection.productIds().isEmpty())
			.toList();
	}

	/**
	 * One menu Product in detail (Decision AI): a UUID is an id, anything else a slug.
	 * @throws vn.hayi.shop.shared.error.DomainException {@code BRANCH_NOT_FOUND}, or {@code CATALOG_PRODUCT_NOT_FOUND}
	 * unless the Product is on the Branch's menu (AH1)
	 */
	@Transactional(readOnly = true)
	public ProductDetail product(UUID branchId, String productIdOrSlug) {
		this.branches.requireActive(branchId);
		Optional<Product> found = asUuid(productIdOrSlug).map(this.products::findById)
			.orElseGet(() -> this.products.findBySlug(productIdOrSlug));
		Product product = found.orElseThrow(() -> notFound(productIdOrSlug));
		BranchProductListing listing = this.listings.findByBranchIdAndProductId(branchId, product.getId())
			.orElse(null);
		Map<UUID, BranchOptionItemListing> optionListings = new HashMap<>();
		this.optionListings.findByIdBranchId(branchId)
			.forEach(option -> optionListings.put(option.getOptionItemId(), option));
		BranchOffer offer = BranchOffer
			.of(product, listing, groupsOf(List.of(product)), optionListings, local(this.clock.instant()))
			.orElseThrow(() -> notFound(productIdOrSlug));
		List<ProductImage> productImages = product.getImages();
		Map<UUID, String> urls = this.images
			.urls(productImages.stream().map(ProductImage::getStoredObjectId).toList());
		return new ProductDetail(product.getId(), product.getSlug(), product.getName(), product.getDescription(),
				productImages.stream()
					.map(image -> new DetailImage(urls.get(image.getStoredObjectId()), image.getAltText(),
							image.isPrimary()))
					.toList(),
				offer.sellability().availability().name(),
				offer.variants()
					.stream()
					.map(variant -> new DetailVariant(variant.variantId(), variant.name(), variant.price(),
							variant.isDefault(), variant.availability().name()))
					.toList(),
				offer.groups()
					.stream()
					.map(group -> new DetailGroup(group.optionGroupId(), group.name(), group.selectionType(),
							group.minSelect(), group.maxSelect(),
							group.items()
								.stream()
								.map(item -> new DetailItem(item.optionItemId(), item.name(), item.extraPrice(),
										item.isDefaultSelected(), item.availability().name()))
								.toList()))
					.toList(),
				SellingScheduleSummary.of(product.getSellingSchedules()));
	}

	public record DetailVariant(UUID variantId, String name, long price, boolean isDefault, String availability) {
	}

	public record DetailGroup(UUID optionGroupId, String name, String selectionType, int minSelect, int maxSelect,
			List<DetailItem> items) {
	}

	public record DetailItem(UUID optionItemId, String name, long extraPrice, boolean isDefaultSelected,
			String availability) {
	}

	/** Selling hours are local to the Branch (Decision AK2); every Branch uses the operational zone today. */
	static LocalDateTime local(Instant instant) {
		return LocalDateTime.ofInstant(instant, BranchTime.DEFAULT_ZONE);
	}

	private static Optional<UUID> asUuid(String value) {
		try {
			return Optional.of(UUID.fromString(value));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	private static DomainException notFound(String productIdOrSlug) {
		return new DomainException(CatalogErrorCode.CATALOG_PRODUCT_NOT_FOUND,
				"No Product " + productIdOrSlug + " on this menu");
	}

	/** {@code sellingScheduleSummary} is {@code null} when the Product has no schedule (Decision AK3). */
	public record ProductDetail(UUID productId, String slug, String name, String description, List<DetailImage> images,
			String availability, List<DetailVariant> variants, List<DetailGroup> optionGroups,
			String sellingScheduleSummary) {
	}

	public record DetailImage(String url, String altText, boolean isPrimary) {
	}

	/** {@code ACTIVE} Categories that hold a menu Product themselves or in a child, by {@code sortOrder} (AH1). */
	private List<MenuCategory> tree(Map<UUID, List<MenuProduct>> byCategory) {
		List<Category> active = this.categories.findByStatusOrderBySortOrderAscIdAsc(CategoryStatus.ACTIVE);
		Map<UUID, String> categoryImages = this.images.urls(active.stream()
			.map(Category::getImageObjectId)
			.filter(Objects::nonNull)
			.toList());
		List<MenuCategory> roots = new ArrayList<>();
		for (Category root : active) {
			if (root.getParentId() != null) {
				continue;
			}
			List<MenuCategory> children = active.stream()
				.filter(child -> root.getId().equals(child.getParentId()))
				.filter(child -> byCategory.containsKey(child.getId()))
				.map(child -> node(child, byCategory, categoryImages, List.of()))
				.toList();
			if (byCategory.containsKey(root.getId()) || !children.isEmpty()) {
				roots.add(node(root, byCategory, categoryImages, children));
			}
		}
		return roots;
	}

	private static MenuCategory node(Category category, Map<UUID, List<MenuProduct>> byCategory,
			Map<UUID, String> categoryImages, List<MenuCategory> children) {
		return new MenuCategory(category.getId(), category.getName(), category.getSlug(),
				categoryImages.get(category.getImageObjectId()), byCategory.getOrDefault(category.getId(), List.of()),
				children);
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

	private static Optional<UUID> primaryImage(Product product) {
		return product.getImages().stream().filter(ProductImage::isPrimary).map(ProductImage::getStoredObjectId)
			.findFirst();
	}

	public record Menu(UUID branchId, Instant evaluatedAt, List<MenuCategory> categories,
			List<MenuCollection> collections) {
	}

	public record MenuCollection(UUID collectionId, String name, List<UUID> productIds) {
	}

	public record MenuCategory(UUID categoryId, String name, String slug, String imageUrl,
			List<MenuProduct> products, List<MenuCategory> children) {
	}

	public record MenuProduct(UUID productId, String slug, String name, String primaryImageUrl, long fromPrice,
			String availability, List<MenuTag> tags) {
	}

	public record MenuTag(String slug, String name) {
	}

}
