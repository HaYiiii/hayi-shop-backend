package vn.hayi.shop.cart.application;

import java.util.List;
import java.util.Map;

import vn.hayi.shop.cart.domain.CartErrorCode;
import vn.hayi.shop.shared.error.DomainException;

/** The resolver found issues with an item to add → {@code 422 CART_ITEM_INVALID} with {@code issues[]} (AV2). */
public class CartItemInvalidException extends DomainException {

	private final List<CartView.ItemIssue> issues;

	CartItemInvalidException(List<CartView.ItemIssue> issues) {
		super(CartErrorCode.CART_ITEM_INVALID, "Cart item refused: " + issues);
		this.issues = List.copyOf(issues);
	}

	public List<CartView.ItemIssue> issues() {
		return this.issues;
	}

	@Override
	public Map<String, Object> problemProperties() {
		return Map.of("issues", this.issues.stream().map(CartView.ItemIssue::asProblemEntry).toList());
	}

}
