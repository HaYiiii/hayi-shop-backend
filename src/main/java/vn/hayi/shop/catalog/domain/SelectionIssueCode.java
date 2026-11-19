package vn.hayi.shop.catalog.domain;

/** Why a selection cannot be ordered as it is (Catalog §5.8, API 02 §2, Decision AJ2). */
public enum SelectionIssueCode {

	PRODUCT_NOT_FOUND, PRODUCT_SOLD_OUT, PRODUCT_PAUSED, VARIANT_SOLD_OUT, VARIANT_NOT_AVAILABLE, OPTION_ITEM_SOLD_OUT,
	OPTION_SELECTION_INVALID, REQUIRED_OPTION_GROUP_UNSATISFIABLE, OUTSIDE_SELLING_HOURS

}
