package vn.hayi.shop.staff.api;

import vn.hayi.shop.shared.error.ErrorCode;
import vn.hayi.shop.shared.error.ErrorKind;

/** Error codes of Staff Operations (API 10 §4, Decision AQ2–AQ3, AQ5). */
public enum StaffErrorCode implements ErrorCode {

	STAFF_NOT_FOUND(ErrorKind.NOT_FOUND),
	STAFF_EMPLOYEE_CODE_TAKEN(ErrorKind.CONFLICT),
	STAFF_ALREADY_EXISTS_FOR_USER(ErrorKind.CONFLICT),
	ASSIGNMENT_PRIMARY_CONFLICT(ErrorKind.BUSINESS_RULE),
	ASSIGNMENT_DUPLICATE_ACTIVE(ErrorKind.BUSINESS_RULE);

	private final ErrorKind kind;

	StaffErrorCode(ErrorKind kind) {
		this.kind = kind;
	}

	@Override
	public ErrorKind kind() {
		return this.kind;
	}

}
