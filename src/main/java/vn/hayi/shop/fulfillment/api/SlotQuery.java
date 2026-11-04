package vn.hayi.shop.fulfillment.api;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * PICKUP slots of a Branch: {@code fromDate} defaults to today and {@code days} to {@code min(7, maxAdvanceDays)}
 * (API 02 §3, Decision T1); {@code itemCount}, when given, is checked against each slot's item capacity (T5).
 */
public record SlotQuery(UUID branchId, LocalDate fromDate, Integer days, OrderType orderType, Integer itemCount) {

	public static final int DEFAULT_DAYS = 7;

	public SlotQuery {
		Objects.requireNonNull(branchId, "branchId");
		orderType = (orderType != null) ? orderType : OrderType.INDIVIDUAL;
	}

}
