package vn.hayi.shop.cart.domain;

import vn.hayi.shop.shared.error.ErrorCode;
import vn.hayi.shop.shared.error.ErrorKind;

/** Error codes of the Cart & Pricing context (API README §6, Decision AV2). */
public enum CartErrorCode implements ErrorCode {

	/** The Catalog resolver found an issue with the item; {@code issues[]} lists them. */
	CART_ITEM_INVALID,
	/** One item would hold more than {@link Cart#MAX_QUANTITY}. */
	CART_ITEM_QUANTITY_LIMIT,
	/** A new item when the Cart already holds {@link Cart#MAX_ITEMS}. */
	CART_ITEM_LIMIT;

	@Override
	public ErrorKind kind() {
		return ErrorKind.BUSINESS_RULE;
	}

}
