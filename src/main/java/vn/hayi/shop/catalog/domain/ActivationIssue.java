package vn.hayi.shop.catalog.domain;

/** Why a Product cannot be activated (Decision AA2); {@code OPTION_CONFIG_INVALID} comes with BE #64 (W4). */
public enum ActivationIssue {

	NO_ACTIVE_VARIANT, PRIMARY_CATEGORY_MISSING, PRIMARY_CATEGORY_INACTIVE

}
