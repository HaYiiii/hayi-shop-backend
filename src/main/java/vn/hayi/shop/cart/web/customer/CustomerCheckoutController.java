package vn.hayi.shop.cart.web.customer;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.cart.application.CheckoutPreview;
import vn.hayi.shop.cart.application.CheckoutPreviewService;
import vn.hayi.shop.identity.api.CustomerContacts;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.web.InvalidFieldsException;
import vn.hayi.shop.platform.web.spec.customer.api.CheckoutApi;
import vn.hayi.shop.platform.web.spec.customer.model.CheckoutIssueCodeDto;
import vn.hayi.shop.platform.web.spec.customer.model.CheckoutIssueDto;
import vn.hayi.shop.platform.web.spec.customer.model.CheckoutIssueSeverityDto;
import vn.hayi.shop.platform.web.spec.customer.model.CheckoutLineDto;
import vn.hayi.shop.platform.web.spec.customer.model.CheckoutPaymentOptionDto;
import vn.hayi.shop.platform.web.spec.customer.model.CheckoutPreviewRequestDto;
import vn.hayi.shop.platform.web.spec.customer.model.CheckoutPreviewRequestFulfillmentDto;
import vn.hayi.shop.platform.web.spec.customer.model.CheckoutPricingResultDto;
import vn.hayi.shop.platform.web.spec.customer.model.CheckoutPricingResultFulfillmentDto;
import vn.hayi.shop.platform.web.spec.customer.model.CheckoutPricingResultRecipientDto;
import vn.hayi.shop.platform.web.spec.customer.model.CheckoutTotalsDto;

/**
 * Checkout preview of the signed-in Customer's Cart (API 03 §2, Decision AZ). Whose Cart it is comes from the session
 * alone (API 03 §4).
 */
@RestController
class CustomerCheckoutController implements CheckoutApi {

	private final CheckoutPreviewService checkout;

	private final CustomerContacts contacts;

	CustomerCheckoutController(CheckoutPreviewService checkout, CustomerContacts contacts) {
		this.checkout = checkout;
		this.contacts = contacts;
	}

	@Override
	public ResponseEntity<CheckoutPricingResultDto> previewCheckout(UUID branchId, CheckoutPreviewRequestDto body,
			String xCsrfToken) {
		List<InvalidFieldsException.Violation> violations = new ArrayList<>();
		if (body.getCouponCode() != null) {
			// Promotions come in S11 (Decision AZ1): refused rather than ignored.
			violations.add(new InvalidFieldsException.Violation("couponCode", "NOT_SUPPORTED"));
		}
		CheckoutPreviewRequestFulfillmentDto fulfillment = body.getFulfillment();
		boolean scheduled = fulfillment.getTiming() == CheckoutPreviewRequestFulfillmentDto.TimingEnum.SCHEDULED;
		LocalTime slotStart = null;
		if (scheduled) {
			if (fulfillment.getRequestedDate() == null) {
				violations.add(new InvalidFieldsException.Violation("fulfillment.requestedDate", "REQUIRED"));
			}
			if (fulfillment.getSlotStart() == null) {
				violations.add(new InvalidFieldsException.Violation("fulfillment.slotStart", "REQUIRED"));
			}
			else {
				slotStart = LocalTime.parse(fulfillment.getSlotStart());
			}
		}
		CheckoutPreview.Recipient recipient = null;
		if (body.getRecipient() != null) {
			String name = body.getRecipient().getName().strip();
			if (name.isEmpty()) {
				violations.add(new InvalidFieldsException.Violation("recipient.name", "NOT_BLANK"));
			}
			String phone = this.contacts.normalizePhone(body.getRecipient().getPhone()).orElse(null);
			if (phone == null) {
				violations.add(new InvalidFieldsException.Violation("recipient.phone", "PHONE_INVALID"));
			}
			recipient = new CheckoutPreview.Recipient(name, phone);
		}
		if (!violations.isEmpty()) {
			throw new InvalidFieldsException(violations);
		}
		CheckoutPreview preview = this.checkout.preview(AuthenticatedSession.require().userId(), branchId,
				new CheckoutPreviewService.Request(scheduled, fulfillment.getRequestedDate(), slotStart, recipient,
						(body.getPaymentMethod() != null) ? body.getPaymentMethod().getValue() : null));
		return ResponseEntity.ok(toDto(preview));
	}

	private static CheckoutPricingResultDto toDto(CheckoutPreview preview) {
		CheckoutPreview.Totals totals = preview.totals();
		CheckoutPreview.Fulfillment fulfillment = preview.fulfillment();
		return new CheckoutPricingResultDto(
				CheckoutPricingResultDto.PricingResultEnum.fromValue(preview.pricingResult().name()),
				preview.fingerprint(), preview.cartVersion(),
				preview.lines().stream().map(CustomerCheckoutController::toDto).toList(), List.of(), null,
				new CheckoutTotalsDto(totals.grossProductSubtotal(), totals.productDiscountTotal(),
						totals.orderDiscountTotal(), totals.deliveryFee(), totals.deliveryDiscountTotal(),
						totals.grandTotal()),
				new CheckoutPricingResultFulfillmentDto(
						fulfillment.ok() ? CheckoutPricingResultFulfillmentDto.StatusEnum.OK
								: CheckoutPricingResultFulfillmentDto.StatusEnum.BLOCKED,
						fulfillment.requestedDate(), time(fulfillment.slotStart()), time(fulfillment.slotEnd())),
				new CheckoutPricingResultRecipientDto(preview.recipient().name(), preview.recipient().phone()),
				toDto(preview.payment()),
				preview.availablePaymentMethods().stream().map(CustomerCheckoutController::toDto).toList(),
				preview.issues().stream().map(CustomerCheckoutController::toDto).toList());
	}

	private static CheckoutLineDto toDto(CheckoutPreview.Line line) {
		return new CheckoutLineDto(line.cartItemId(), line.quantity(), line.unitPrice(), line.previousUnitPrice(),
				CheckoutLineDto.PriceChangeEnum.fromValue(line.priceChange().name()), line.grossLineTotal(),
				line.productDiscountAmount(), line.orderDiscountAllocated(), line.netLineTotal(),
				line.issues().stream().map(CustomerCheckoutController::toDto).toList());
	}

	private static CheckoutPaymentOptionDto toDto(CheckoutPreview.PaymentOption option) {
		return new CheckoutPaymentOptionDto(CheckoutPaymentOptionDto.MethodEnum.fromValue(option.method()),
				CheckoutPaymentOptionDto.PrepaymentRequirementEnum.fromValue(option.prepaymentRequirement()),
				option.requiredPrepaymentAmount())
			.paymentDueInMinutes(option.paymentDueInMinutes());
	}

	private static CheckoutIssueDto toDto(CheckoutPreview.Issue issue) {
		return new CheckoutIssueDto(CheckoutIssueCodeDto.fromValue(issue.code()),
				CheckoutIssueSeverityDto.fromValue(issue.severity().name()))
			.cartItemId(issue.cartItemId());
	}

	private static String time(LocalTime time) {
		return (time != null) ? time.toString() : null;
	}

}
