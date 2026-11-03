package vn.hayi.shop.branch.domain;

import vn.hayi.shop.shared.error.ErrorCode;
import vn.hayi.shop.shared.error.ErrorKind;

/** Error codes of Branch (API 09 §4). */
public enum BranchErrorCode implements ErrorCode {

	BRANCH_NOT_FOUND(ErrorKind.NOT_FOUND),
	BRANCH_CODE_TAKEN(ErrorKind.CONFLICT),
	BRANCH_INVALID_TRANSITION(ErrorKind.CONFLICT);

	private final ErrorKind kind;

	BranchErrorCode(ErrorKind kind) {
		this.kind = kind;
	}

	@Override
	public ErrorKind kind() {
		return this.kind;
	}

}
