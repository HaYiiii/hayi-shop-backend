package vn.hayi.shop.fulfillment.domain;

import vn.hayi.shop.shared.error.ErrorCode;
import vn.hayi.shop.shared.error.ErrorKind;

/** Error codes of Fulfillment (API 09 §4). */
public enum FulfillmentErrorCode implements ErrorCode {

	FULFILLMENT_CONFIG_INVALID(ErrorKind.BUSINESS_RULE),
	FULFILLMENT_SCHEDULE_OVERLAP(ErrorKind.BUSINESS_RULE),
	FULFILLMENT_OUTSIDE_BRANCH_HOURS(ErrorKind.BUSINESS_RULE),
	FULFILLMENT_OVERRIDE_NOT_FOUND(ErrorKind.NOT_FOUND);

	private final ErrorKind kind;

	FulfillmentErrorCode(ErrorKind kind) {
		this.kind = kind;
	}

	@Override
	public ErrorKind kind() {
		return this.kind;
	}

}
