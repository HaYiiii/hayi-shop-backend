package vn.hayi.shop.catalog.web.publicapi;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.catalog.application.PublicMenuService;
import vn.hayi.shop.platform.web.spec.publicapi.api.PublicMenuApi;
import vn.hayi.shop.platform.web.spec.publicapi.model.MenuAvailabilityDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.MenuCategoryDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.MenuProductDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.PublicMenuDto;

/** The public menu of a Branch (API 02 §2, Decision AH); cached 30 seconds, no sign-in. */
@RestController
class PublicMenuController implements PublicMenuApi {

	private static final CacheControl THIRTY_SECONDS = CacheControl.maxAge(30, TimeUnit.SECONDS).cachePublic();

	private final PublicMenuService menus;

	PublicMenuController(PublicMenuService menus) {
		this.menus = menus;
	}

	@Override
	public ResponseEntity<PublicMenuDto> getBranchMenu(UUID branchId, OffsetDateTime at) {
		PublicMenuService.Menu menu = this.menus.menu(branchId, (at != null) ? at.toInstant() : null);
		return ResponseEntity.ok()
			.cacheControl(THIRTY_SECONDS)
			.body(new PublicMenuDto(menu.branchId(), menu.evaluatedAt().atOffset(ZoneOffset.UTC),
					menu.categories().stream().map(PublicMenuController::toDto).toList(), List.of()));
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
