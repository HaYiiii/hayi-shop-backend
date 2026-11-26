package vn.hayi.shop.cart.domain;

import vn.hayi.shop.shared.error.ErrorCode;
import vn.hayi.shop.shared.error.ErrorKind;

/** Error codes of the Cart & Pricing context (API README §6, Decision AV2, AW3). */
public enum CartErrorCode implements ErrorCode {

	/** The Catalog resolver found an issue with the item; {@code issues[]} lists them. */
	CART_ITEM_INVALID(ErrorKind.BUSINESS_RULE),
	/** One item would hold more than {@link Cart#MAX_QUANTITY}. */
	CART_ITEM_QUANTITY_LIMIT(ErrorKind.BUSINESS_RULE),
	/** A new item when the Cart already holds {@link Cart#MAX_ITEMS}. */
	CART_ITEM_LIMIT(ErrorKind.BUSINESS_RULE),
	/** The item is not in the caller's Cart. */
	CART_ITEM_NOT_FOUND(ErrorKind.NOT_FOUND);

	private final ErrorKind kind;

	CartErrorCode(ErrorKind kind) {
		this.kind = kind;
	}

	@Override
	public ErrorKind kind() {
		return this.kind;
	}

}
