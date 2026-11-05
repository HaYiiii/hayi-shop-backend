package vn.hayi.shop.catalog.domain;

import vn.hayi.shop.shared.error.ErrorCode;
import vn.hayi.shop.shared.error.ErrorKind;

/** Error codes of Catalog (API 08 §4). */
public enum CatalogErrorCode implements ErrorCode {

	CATALOG_SLUG_TAKEN(ErrorKind.CONFLICT),
	CATALOG_CATEGORY_NOT_FOUND(ErrorKind.NOT_FOUND),
	CATALOG_CATEGORY_ARCHIVED(ErrorKind.CONFLICT),
	CATALOG_CATEGORY_IN_USE(ErrorKind.CONFLICT),
	CATALOG_CATEGORY_ORDER_STALE(ErrorKind.CONFLICT),
	CATALOG_CATEGORY_DEPTH_EXCEEDED(ErrorKind.BUSINESS_RULE);

	private final ErrorKind kind;

	CatalogErrorCode(ErrorKind kind) {
		this.kind = kind;
	}

	@Override
	public ErrorKind kind() {
		return this.kind;
	}

}
