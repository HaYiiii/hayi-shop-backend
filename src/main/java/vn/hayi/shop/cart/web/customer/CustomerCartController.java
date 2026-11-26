package vn.hayi.shop.cart.web.customer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.cart.application.CartService;
import vn.hayi.shop.cart.application.CartView;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.web.InvalidFieldsException;
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
		return ResponseEntity.ok(toDto(this.carts.view(userId, branchId, at)));
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
