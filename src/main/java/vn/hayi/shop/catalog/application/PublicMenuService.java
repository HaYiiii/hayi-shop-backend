package vn.hayi.shop.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.branch.api.BranchDirectory;
import vn.hayi.shop.catalog.domain.AvailabilityState;
import vn.hayi.shop.catalog.domain.BranchListingRepository;
import vn.hayi.shop.catalog.domain.BranchOptionItemListingRepository;
import vn.hayi.shop.catalog.domain.BranchProductListing;
import vn.hayi.shop.catalog.domain.BranchSellability;
import vn.hayi.shop.catalog.domain.Category;
import vn.hayi.shop.catalog.domain.CategoryRepository;
import vn.hayi.shop.catalog.domain.CategoryStatus;
import vn.hayi.shop.catalog.domain.OptionGroup;
import vn.hayi.shop.catalog.domain.OptionGroupRepository;
import vn.hayi.shop.catalog.domain.Product;
import vn.hayi.shop.catalog.domain.ProductImage;
import vn.hayi.shop.catalog.domain.ProductOptionGroupConfig;
import vn.hayi.shop.catalog.domain.ProductRepository;
import vn.hayi.shop.catalog.domain.ProductStatus;

/**
 * The menu customers see at one Branch (API 02 §2, Catalog §5.6, Decision F, W3, W6, AH): read on each request
 * from the Products, their listings there, the option overrides and the Category tree.
 */
@Service
public class PublicMenuService {

	private static final Comparator<Product> MENU_ORDER = Comparator.comparingInt(Product::getSortOrder)
		.thenComparing(Product::getName)
		.thenComparing(Product::getId);

	private final BranchDirectory branches;

	private final BranchListingRepository listings;

	private final BranchOptionItemListingRepository optionListings;

	private final ProductRepository products;

	private final OptionGroupRepository optionGroups;

	private final CategoryRepository categories;

	private final CatalogImages images;

	private final Clock clock;

	PublicMenuService(BranchDirectory branches, BranchListingRepository listings,
			BranchOptionItemListingRepository optionListings, ProductRepository products,
			OptionGroupRepository optionGroups, CategoryRepository categories, CatalogImages images, Clock clock) {
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
		Map<UUID, List<MenuProduct>> byCategory = new LinkedHashMap<>();
		found.stream().sorted(MENU_ORDER).forEach(product -> BranchSellability
			.of(product, byProduct.get(product.getId()), groups, optionStates)
			.ifPresent(sellability -> byCategory.computeIfAbsent(product.getPrimaryCategoryId(), id -> new ArrayList<>())
				.add(new MenuProduct(product.getId(), product.getSlug(), product.getName(),
						primaryImage(product).map(imageUrls::get).orElse(null), sellability.fromPrice(),
						sellability.availability().name()))));
		return new Menu(branchId, evaluatedAt, tree(byCategory));
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

	public record Menu(UUID branchId, Instant evaluatedAt, List<MenuCategory> categories) {
	}

	public record MenuCategory(UUID categoryId, String name, String slug, String imageUrl,
			List<MenuProduct> products, List<MenuCategory> children) {
	}

	public record MenuProduct(UUID productId, String slug, String name, String primaryImageUrl, long fromPrice,
			String availability) {
	}

}
