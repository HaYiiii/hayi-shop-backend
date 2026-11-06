package vn.hayi.shop.catalog.domain;

/** Why a Product cannot be activated (Decision AA2, W4/AC4). */
public enum ActivationIssue {

	NO_ACTIVE_VARIANT, PRIMARY_CATEGORY_MISSING, PRIMARY_CATEGORY_INACTIVE, OPTION_CONFIG_INVALID

}
