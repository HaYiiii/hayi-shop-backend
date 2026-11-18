package vn.hayi.shop.catalog.web.backoffice;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.catalog.application.BranchListingService;
import vn.hayi.shop.catalog.application.BranchListingService.BranchOptionItemListingView;
import vn.hayi.shop.catalog.application.BranchListingView;
import vn.hayi.shop.catalog.application.CatalogClient;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.Permission;
import vn.hayi.shop.platform.security.RequiresPermission;
import vn.hayi.shop.platform.web.VersionTags;
import vn.hayi.shop.platform.web.spec.backoffice.api.BranchListingsApi;
import vn.hayi.shop.platform.web.spec.backoffice.model.AvailabilityStateDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchListingDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchListingInputDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchListingPageDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchListingRowDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchOptionItemListingDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchOptionItemListingInputDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchVariantListingDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchVariantListingInputDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.ProductPagePageDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.ProductStatusDto;
import vn.hayi.shop.shared.actor.ActorContext;

/**
 * Backoffice Branch listing endpoints (API 08 §2, Decision AD). The {@code {branchId}} path variable puts each in that
 * Branch's scope (M1); {@code BRANCH_LISTING_MANAGE} covers the Branch prices too (AD1). The request schemas only take
 * {@code AVAILABLE | NOT_AVAILABLE}, so {@code SOLD_OUT} / {@code PAUSED} never reach the service (W2).
 */
@RestController
class BackofficeBranchListingController implements BranchListingsApi {

	private final BranchListingService listings;

	private final HttpServletRequest request;

	BackofficeBranchListingController(BranchListingService listings, HttpServletRequest request) {
		this.listings = listings;
		this.request = request;
	}

	@Override
	@RequiresPermission(Permission.CATALOG_VIEW)
	public ResponseEntity<BranchListingPageDto> listBranchListings(UUID branchId, Integer page, Integer size,
			String q) {
		Page<BranchListingView> result = this.listings.list(branchId, q, page, size);
		return ResponseEntity.ok(new BranchListingPageDto(
				result.getContent().stream().map(BackofficeBranchListingController::toDto).toList(),
				new ProductPagePageDto(result.getNumber(), result.getSize(), result.getTotalElements(),
						result.getTotalPages())));
	}

	@Override
	@RequiresPermission(Permission.BRANCH_LISTING_MANAGE)
	public ResponseEntity<BranchListingRowDto> putBranchListing(UUID branchId, UUID productId,
			BranchListingInputDto body, String ifMatch, String xCsrfToken) {
		var expected = VersionTags.require(ifMatch);
		return withETag(this.listings.put(branchId, productId, body.getAvailabilityState().getValue(), expected,
				client()));
	}

	@Override
	@RequiresPermission(Permission.BRANCH_LISTING_MANAGE)
	public ResponseEntity<BranchListingRowDto> unlistBranchListing(UUID branchId, UUID productId, String ifMatch,
			String xCsrfToken) {
		return withETag(this.listings.unlist(branchId, productId, VersionTags.optional(ifMatch), client()));
	}

	@Override
	@RequiresPermission(Permission.BRANCH_LISTING_MANAGE)
	public ResponseEntity<BranchListingRowDto> putBranchVariantListing(UUID branchId, UUID productId, UUID variantId,
			BranchVariantListingInputDto body, String ifMatch, String xCsrfToken) {
		var expected = VersionTags.require(ifMatch);
		return withETag(this.listings.putVariant(branchId, productId, variantId, body.getPrice(),
				body.getAvailabilityState().getValue(), expected, client()));
	}

	@Override
	@RequiresPermission(Permission.BRANCH_LISTING_MANAGE)
	public ResponseEntity<BranchOptionItemListingDto> putBranchOptionItemListing(UUID branchId, UUID optionItemId,
			BranchOptionItemListingInputDto body, String ifMatch, String xCsrfToken) {
		var expected = VersionTags.require(ifMatch);
		BranchOptionItemListingView listing = this.listings.putOptionItem(branchId, optionItemId,
				body.getExtraPrice(), expected, client());
		return ResponseEntity.ok()
			.eTag(VersionTags.of(listing.version()))
			.body(new BranchOptionItemListingDto(listing.optionItemId(), listing.extraPrice(),
					AvailabilityStateDto.valueOf(listing.availabilityState()), listing.version()));
	}

	private static ResponseEntity<BranchListingRowDto> withETag(BranchListingView row) {
		return ResponseEntity.ok().eTag(VersionTags.of(row.listing().version())).body(toDto(row));
	}

	private CatalogClient client() {
		ClientInfo client = ClientInfo.from(this.request);
		return new CatalogClient(ActorContext.staff(AuthenticatedSession.require().userId(), null),
				client.ipAddress(), client.userAgent());
	}

	private static BranchListingRowDto toDto(BranchListingView row) {
		BranchListingView.Listing listing = row.listing();
		return new BranchListingRowDto(row.productId(), row.productCode(), row.name(),
				ProductStatusDto.valueOf(row.status()),
				(listing != null) ? new BranchListingDto(AvailabilityStateDto.valueOf(listing.availabilityState()),
						listing.version(),
						listing.variants()
							.stream()
							.map(variant -> new BranchVariantListingDto(variant.variantId(), variant.name(),
									variant.basePrice(), variant.price(), variant.effectivePrice(),
									AvailabilityStateDto.valueOf(variant.availabilityState())))
							.toList())
						: null);
	}

}
