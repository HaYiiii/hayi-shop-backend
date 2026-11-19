package vn.hayi.shop.storage.domain;

import vn.hayi.shop.shared.error.ErrorCode;
import vn.hayi.shop.shared.error.ErrorKind;

/** Error codes of File / Object Storage (API 06 §1, Decision AF2). */
public enum StorageErrorCode implements ErrorCode {

	UPLOAD_NOT_FOUND(ErrorKind.NOT_FOUND),
	UPLOAD_ALREADY_FINALIZED(ErrorKind.CONFLICT),
	UPLOAD_NOT_FOUND_IN_STORAGE(ErrorKind.BUSINESS_RULE),
	UPLOAD_TOO_LARGE(ErrorKind.BUSINESS_RULE),
	UPLOAD_MIME_MISMATCH(ErrorKind.BUSINESS_RULE),
	UPLOAD_CHECKSUM_MISMATCH(ErrorKind.BUSINESS_RULE);

	private final ErrorKind kind;

	StorageErrorCode(ErrorKind kind) {
		this.kind = kind;
	}

	@Override
	public ErrorKind kind() {
		return this.kind;
	}

}
