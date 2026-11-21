package vn.hayi.shop.customer.api;

import vn.hayi.shop.shared.error.ErrorCode;
import vn.hayi.shop.shared.error.ErrorKind;

/** Error codes of the Customer context (API README §6, Decision AN4). */
public enum CustomerErrorCode implements ErrorCode {

	CUSTOMER_ALREADY_REGISTERED(ErrorKind.CONFLICT);

	private final ErrorKind kind;

	CustomerErrorCode(ErrorKind kind) {
		this.kind = kind;
	}

	@Override
	public ErrorKind kind() {
		return this.kind;
	}

}
