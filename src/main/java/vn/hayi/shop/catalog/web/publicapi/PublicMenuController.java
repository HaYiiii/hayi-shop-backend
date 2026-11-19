package vn.hayi.shop.catalog.web.publicapi;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.catalog.api.CatalogSelectionResolver;
import vn.hayi.shop.catalog.application.PublicMenuService;
import vn.hayi.shop.platform.web.spec.publicapi.api.PublicMenuApi;
import vn.hayi.shop.platform.web.spec.publicapi.model.DetailImageDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.DetailOptionGroupDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.DetailOptionItemDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.DetailVariantDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.MenuAvailabilityDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.PublicProductDetailDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.ResolveIssueCodeDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.ResolveIssueDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.ResolveLineDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.ResolveRequestDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.ResolveResponseDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.MenuCategoryDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.MenuProductDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.PublicMenuDto;

/** The public menu of a Branch (API 02 §2, Decision AH); cached 30 seconds, no sign-in. */
@RestController
class PublicMenuController implements PublicMenuApi {

	private static final CacheControl THIRTY_SECONDS = CacheControl.maxAge(30, TimeUnit.SECONDS).cachePublic();

	private final PublicMenuService menus;

	private final CatalogSelectionResolver resolver;

	PublicMenuController(PublicMenuService menus, CatalogSelectionResolver resolver) {
		this.menus = menus;
		this.resolver = resolver;
	}

	@Override
	public ResponseEntity<PublicMenuDto> getBranchMenu(UUID branchId, OffsetDateTime at) {
		PublicMenuService.Menu menu = this.menus.menu(branchId, (at != null) ? at.toInstant() : null);
		return ResponseEntity.ok()
			.cacheControl(THIRTY_SECONDS)
			.body(new PublicMenuDto(menu.branchId(), menu.evaluatedAt().atOffset(ZoneOffset.UTC),
					menu.categories().stream().map(PublicMenuController::toDto).toList(), List.of()));
	}

	/** Writes nothing and is never cached: a cart's prices and states are read fresh (Decision AJ4). */
	@Override
	public ResponseEntity<ResolveResponseDto> resolveCatalog(UUID branchId, ResolveRequestDto body) {
		CatalogSelectionResolver.ResolvedSelections resolved = this.resolver.resolve(branchId,
				body.getLines()
					.stream()
					.map(line -> new CatalogSelectionResolver.SelectionRequest(line.getRef(), line.getProductId(),
							line.getVariantId(), (line.getOptionItemIds() != null) ? line.getOptionItemIds() : List.of(),
							line.getQuantity()))
					.toList(),
				(body.getAt() != null) ? body.getAt().toInstant() : null);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(new ResolveResponseDto(resolved.lines()
				.stream()
				.map(line -> new ResolveLineDto(line.ref(), line.valid(), line.normalizedOptionItemIds(),
						line.unitPrice(), line.lineTotal(),
						line.issues()
							.stream()
							.map(issue -> new ResolveIssueDto(ResolveIssueCodeDto.valueOf(issue.code().name()))
								.optionItemId(issue.optionItemId())
								.optionGroupId(issue.optionGroupId()))
							.toList()))
				.toList(), resolved.fingerprint()));
	}

	@Override
	public ResponseEntity<PublicProductDetailDto> getBranchProduct(UUID branchId, String productIdOrSlug) {
		PublicMenuService.ProductDetail product = this.menus.product(branchId, productIdOrSlug);
		return ResponseEntity.ok()
			.cacheControl(THIRTY_SECONDS)
			.body(new PublicProductDetailDto(product.productId(), product.slug(), product.name(),
					product.description(),
					product.images()
						.stream()
						.map(image -> new DetailImageDto(image.url(), image.altText(), image.isPrimary()))
						.toList(),
					MenuAvailabilityDto.valueOf(product.availability()),
					product.variants()
						.stream()
						.map(variant -> new DetailVariantDto(variant.variantId(), variant.name(), variant.price(),
								variant.isDefault(), MenuAvailabilityDto.valueOf(variant.availability())))
						.toList(),
					product.optionGroups()
						.stream()
						.map(group -> new DetailOptionGroupDto(group.optionGroupId(), group.name(),
								DetailOptionGroupDto.SelectionTypeEnum.fromValue(group.selectionType()),
								group.minSelect(), group.maxSelect(),
								group.items()
									.stream()
									.map(item -> new DetailOptionItemDto(item.optionItemId(), item.name(),
											item.extraPrice(), item.isDefaultSelected(),
											MenuAvailabilityDto.valueOf(item.availability())))
									.toList()))
						.toList(),
					null));
	}

	private static MenuCategoryDto toDto(PublicMenuService.MenuCategory category) {
		return new MenuCategoryDto(category.categoryId(), category.name(), category.slug(), category.imageUrl(),
				category.products()
					.stream()
					.map(product -> new MenuProductDto(product.productId(), product.slug(), product.name(),
							product.primaryImageUrl(), product.fromPrice(),
							MenuAvailabilityDto.valueOf(product.availability()), List.of()))
					.toList(),
				category.children().stream().map(PublicMenuController::toDto).toList());
	}

}
