package vn.hayi.shop.cart.web.customer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import vn.hayi.shop.cart.application.CartService;
import vn.hayi.shop.cart.application.CartView;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.web.InvalidFieldsException;
import vn.hayi.shop.platform.web.MergePatch;
import vn.hayi.shop.platform.web.VersionTags;
import vn.hayi.shop.platform.web.spec.customer.model.CartItemEditDto;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.patch.Change;
import vn.hayi.shop.platform.web.spec.customer.api.CartApi;
import vn.hayi.shop.platform.web.spec.customer.model.AddCartItemRequestDto;
import vn.hayi.shop.platform.web.spec.customer.model.CartDto;
import vn.hayi.shop.platform.web.spec.customer.model.CartIssueCodeDto;
import vn.hayi.shop.platform.web.spec.customer.model.CartIssueDto;
import vn.hayi.shop.platform.web.spec.customer.model.CartIssueSeverityDto;
import vn.hayi.shop.platform.web.spec.customer.model.CartItemDto;
import vn.hayi.shop.platform.web.spec.customer.model.CartItemOptionDto;
import vn.hayi.shop.platform.web.spec.customer.model.CartSummaryDto;
import vn.hayi.shop.platform.web.spec.customer.model.CartSummaryListDto;

/**
 * The signed-in Customer's server Cart (API 03 §1, Decision AV). Whose Cart it is comes from the session alone
 * (API 03 §4): no path or body names a Customer.
 */
@RestController
class CustomerCartController implements CartApi {

	/** As {@code AddCartItemRequest} in the contract. */
	private static final int MAX_OPTION_ITEMS = 20;

	/** Longest note accepted before normalizing, as {@code AddCartItemRequest}. */
	private static final int MAX_NOTE_INPUT = 1024;

	private final CartService carts;

	CustomerCartController(CartService carts) {
		this.carts = carts;
	}

	@Override
	public ResponseEntity<CartDto> getCart(UUID branchId, OffsetDateTime at) {
		return ResponseEntity.ok(toDto(this.carts.view(AuthenticatedSession.require().userId(), branchId, instant(at))));
	}

	@Override
	public ResponseEntity<CartDto> addCartItem(UUID branchId, AddCartItemRequestDto body, String xCsrfToken) {
		String note = CartService.normalizeNote(body.getNote());
		if (note != null && note.length() > CartService.MAX_NOTE_LENGTH) {
			throw new InvalidFieldsException(List.of(new InvalidFieldsException.Violation("note", "TOO_LONG")));
		}
		UUID userId = AuthenticatedSession.require().userId();
		Instant at = instant(body.getAt());
		this.carts.add(userId, branchId, body.getProductId(), body.getVariantId(),
				(body.getOptionItemIds() != null) ? body.getOptionItemIds() : List.of(), body.getQuantity(), note, at);
		// A second read after the add committed: it shows the whole Cart priced now, and may already hold a concurrent
		// add to the same Cart (its version then says so).
		return ResponseEntity.ok(toDto(this.carts.view(userId, branchId, at)));
	}

	@Override
	public ResponseEntity<CartItemEditDto> updateCartItem(UUID branchId, UUID cartItemId, JsonNode body,
			String ifMatch, String xCsrfToken) {
		ExpectedVersion expected = VersionTags.optional(ifMatch);
		MergePatch patch = MergePatch.of(body);
		Change<Integer> quantity = patch.requiredInt("quantity", 1, CartService.MAX_QUANTITY);
		Change<UUID> variantId = patch.requiredUuid("variantId");
		Change<List<UUID>> optionItemIds = patch.requiredUuidList("optionItemIds", MAX_OPTION_ITEMS);
		Change<String> note = patch.optionalText("note", MAX_NOTE_INPUT).map(CartService::normalizeNote);
		Instant at = patch.optionalInstant("at").applyTo(null);
		patch.validate();
		String normalized = note.applyTo(null);
		if (normalized != null && normalized.length() > CartService.MAX_NOTE_LENGTH) {
			throw new InvalidFieldsException(List.of(new InvalidFieldsException.Violation("note", "TOO_LONG")));
		}
		UUID userId = AuthenticatedSession.require().userId();
		CartService.Edited edited = this.carts.edit(userId, branchId, cartItemId,
				new CartService.ItemPatch(quantity, variantId, optionItemIds, note), expected, at);
		return ResponseEntity.ok(new CartItemEditDto(toDto(this.carts.view(userId, branchId, at)),
				edited.cartItemId(), edited.merged()));
	}

	@Override
	public ResponseEntity<CartDto> removeCartItem(UUID branchId, UUID cartItemId, String ifMatch,
			String xCsrfToken) {
		ExpectedVersion expected = VersionTags.optional(ifMatch);
		UUID userId = AuthenticatedSession.require().userId();
		this.carts.remove(userId, branchId, cartItemId, expected);
		return ResponseEntity.ok(toDto(this.carts.view(userId, branchId, null)));
	}

	@Override
	public ResponseEntity<CartDto> clearCart(UUID branchId, String ifMatch, String xCsrfToken) {
		ExpectedVersion expected = VersionTags.optional(ifMatch);
		UUID userId = AuthenticatedSession.require().userId();
		this.carts.clear(userId, branchId, expected);
		return ResponseEntity.ok(toDto(this.carts.view(userId, branchId, null)));
	}

	@Override
	public ResponseEntity<CartSummaryListDto> listCarts() {
		return ResponseEntity.ok(new CartSummaryListDto(this.carts.list(AuthenticatedSession.require().userId())
			.stream()
			.map(cart -> new CartSummaryDto(cart.cartId(), cart.branchId(), cart.itemCount(),
					cart.updatedAt().atOffset(ZoneOffset.UTC)))
			.toList()));
	}

	private static Instant instant(OffsetDateTime at) {
		return (at != null) ? at.toInstant() : null;
	}

	private static CartDto toDto(CartView cart) {
		return new CartDto(cart.cartId(), cart.branchId(), cart.version(),
				cart.items().stream().map(CustomerCartController::toDto).toList(), cart.couponCode(),
				cart.provisionalSubtotal(), cart.hasBlockingIssues(),
				(cart.updatedAt() != null) ? cart.updatedAt().atOffset(ZoneOffset.UTC) : null);
	}

	private static CartItemDto toDto(CartView.Item item) {
		return new CartItemDto(item.cartItemId(), item.productId(), item.productName(), item.variantId(),
				item.variantName(),
				item.options()
					.stream()
					.map(option -> new CartItemOptionDto(option.optionItemId(), option.optionGroupName(),
							option.optionItemName(), option.extraPrice()))
					.toList(),
				item.note(), item.quantity(), item.unitPrice(), item.lineTotal(),
				item.issues()
					.stream()
					.map(issue -> new CartIssueDto(CartIssueCodeDto.valueOf(issue.code()),
							CartIssueSeverityDto.valueOf(CartView.ItemIssue.SEVERITY))
						.optionItemId(issue.optionItemId())
						.optionGroupId(issue.optionGroupId()))
					.toList());
	}

}
