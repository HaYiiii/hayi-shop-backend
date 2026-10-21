package vn.hayi.shop.platform.security;

/**
 * Permission catalog v1 (API 01-security §7, Decision G4), the same codes as the {@code iam_permission} seed. Codes
 * marked <b>S</b> in the matrix require a fresh step-up: their endpoints carry {@link RequiresStepUp} (Decision M5).
 */
public enum Permission {

	// §7.1 Catalog / Branch / Fulfillment
	CATALOG_VIEW,
	CATALOG_MANAGE,
	CATALOG_PRICE_MANAGE,
	BRANCH_LISTING_MANAGE,
	SOLD_OUT_MANAGE,
	BRANCH_VIEW,
	/** Only closing a Branch needs step-up: that endpoint adds {@link RequiresStepUp} itself. */
	BRANCH_MANAGE,
	FULFILLMENT_CONFIG_MANAGE,

	// §7.2 Order / Preparation
	ORDER_VIEW,
	ORDER_CREATE_ON_BEHALF,
	ORDER_CONFIRM,
	ORDER_PREPARE,
	ORDER_READY_OVERRIDE,
	ORDER_COMPLETE,
	ORDER_CANCEL,
	ORDER_CANCEL_PRIVILEGED,
	ORDER_AMEND,
	GROUP_ORDER_VIEW,

	// §7.3 Payment / Refund
	PAYMENT_VIEW,
	PAYMENT_VERIFY,
	PAYMENT_HOLD,
	PAYMENT_CONFIG_MANAGE(true),
	REFUND_REQUEST,
	REFUND_APPROVE(true),
	REFUND_PROCESS,

	// §7.4 Promotion / Loyalty / Customer
	PROMOTION_VIEW,
	PROMOTION_MANAGE,
	PROMOTION_OVERRIDE(true),
	LOYALTY_VIEW,
	LOYALTY_ADJUST(true),
	LOYALTY_RECOVERY_WAIVE(true),
	LOYALTY_CONFIG_MANAGE,
	CUSTOMER_VIEW,
	CUSTOMER_PII_VIEW,

	// §7.5 Inventory
	INVENTORY_VIEW,
	INVENTORY_RECEIVE,
	INVENTORY_WASTE,
	INVENTORY_ADJUST,
	INVENTORY_CANCELLATION_RESOLVE,
	STOCKTAKE_PERFORM,
	STOCKTAKE_APPROVE,
	INVENTORY_MASTER_MANAGE,
	INVENTORY_SETTING_MANAGE,

	// §7.6 Staff / Security / Report / Operations
	STAFF_VIEW,
	STAFF_MANAGE(true),
	ROLE_ASSIGN(true),
	REPORT_VIEW_OPERATIONAL,
	REPORT_VIEW_FINANCIAL,
	REPORT_EXPORT(true),
	DASHBOARD_MANAGE,
	NOTIFICATION_TEMPLATE_MANAGE,
	AUDIT_VIEW,
	SECURITY_EVENT_VIEW,
	JOB_OPERATE(true);

	private final boolean requiresStepUp;

	Permission() {
		this(false);
	}

	Permission(boolean requiresStepUp) {
		this.requiresStepUp = requiresStepUp;
	}

	/** Marked <b>S</b> in the permission matrix. */
	public boolean requiresStepUp() {
		return this.requiresStepUp;
	}

}
