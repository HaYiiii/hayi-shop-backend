package vn.hayi.shop.cart.application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * A priced PICKUP checkout of one Cart (API 03 §2, Decision AZ): nothing is claimed or written. Discounts and the
 * delivery fee are always 0 in S4 (no Promotion until S11, PICKUP only).
 */
public record CheckoutPreview(PricingResult pricingResult, String fingerprint, long cartVersion, List<Line> lines,
		Totals totals, Fulfillment fulfillment, Recipient recipient, PaymentOption payment,
		List<PaymentOption> availablePaymentMethods, List<Issue> issues) {

	public CheckoutPreview {
		lines = List.copyOf(lines);
		availablePaymentMethods = List.copyOf(availablePaymentMethods);
		issues = List.copyOf(issues);
	}

	public enum PricingResult {

		VALID, REQUIRES_ACKNOWLEDGMENT, BLOCKED

	}

	public enum Severity {

		BLOCKING, ACK_REQUIRED, INFO

	}

	public enum PriceChange {

		UNCHANGED, INCREASED, DECREASED

	}

	/** @param cartItemId the item it is about, or {@code null} for the whole checkout */
	public record Issue(String code, Severity severity, UUID cartItemId) {
	}

	/** {@code unitPrice} and the totals are {@code null} when the Product or size is unknown. */
	public record Line(UUID cartItemId, int quantity, Long unitPrice, Long previousUnitPrice, PriceChange priceChange,
			Long grossLineTotal, long productDiscountAmount, long orderDiscountAllocated, Long netLineTotal,
			List<Issue> issues) {

		public Line {
			issues = List.copyOf(issues);
		}

	}

	public record Totals(long grossProductSubtotal, long productDiscountTotal, long orderDiscountTotal,
			long deliveryFee, long deliveryDiscountTotal, long grandTotal) {
	}

	/** {@code ok} is {@code false} when a Fulfillment issue blocks the order. */
	public record Fulfillment(boolean ok, LocalDate requestedDate, LocalTime slotStart, LocalTime slotEnd) {
	}

	public record Recipient(String name, String phone) {
	}

	public record PaymentOption(String method, String prepaymentRequirement, long requiredPrepaymentAmount,
			Integer paymentDueInMinutes) {
	}

}
