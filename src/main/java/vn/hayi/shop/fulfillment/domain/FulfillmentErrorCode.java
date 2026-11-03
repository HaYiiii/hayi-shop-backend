package vn.hayi.shop.fulfillment.domain;

import vn.hayi.shop.shared.error.ErrorCode;
import vn.hayi.shop.shared.error.ErrorKind;

/** Error codes of Fulfillment (API 09 §4). */
public enum FulfillmentErrorCode implements ErrorCode {

	FULFILLMENT_CONFIG_INVALID(ErrorKind.BUSINESS_RULE);

	private final ErrorKind kind;

	FulfillmentErrorCode(ErrorKind kind) {
		this.kind = kind;
	}

	@Override
	public ErrorKind kind() {
		return this.kind;
	}

}
