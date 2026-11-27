package vn.hayi.shop.cart.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.cart.application.CheckoutPreview.Issue;
import vn.hayi.shop.cart.application.CheckoutPreview.Line;
import vn.hayi.shop.cart.application.CheckoutPreview.PaymentOption;
import vn.hayi.shop.cart.application.CheckoutPreview.PriceChange;
import vn.hayi.shop.cart.application.CheckoutPreview.PricingResult;
import vn.hayi.shop.cart.application.CheckoutPreview.Recipient;
import vn.hayi.shop.cart.application.CheckoutPreview.Severity;
import vn.hayi.shop.cart.domain.Cart;
import vn.hayi.shop.cart.domain.CartItem;
import vn.hayi.shop.cart.domain.CartItemOption;
import vn.hayi.shop.cart.domain.CartRepository;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver.ResolvedLine;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver.SelectionRequest;
import vn.hayi.shop.customer.api.CustomerErrorCode;
import vn.hayi.shop.customer.api.CustomerProfiles;
import vn.hayi.shop.fulfillment.api.OrderType;
import vn.hayi.shop.fulfillment.api.PickupSlot;
import vn.hayi.shop.fulfillment.api.PickupSlots;
import vn.hayi.shop.fulfillment.api.SlotDay;
import vn.hayi.shop.fulfillment.api.SlotQuery;
import vn.hayi.shop.identity.api.CustomerContacts;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.error.RuleViolationsException;
import vn.hayi.shop.shared.time.BranchTime;

/**
 * Checkout preview of the caller's Cart for a PICKUP order (Cart & Pricing §6.4–§6.7, API 03 §2, Decision AZ).
 * Everything is recomputed from the Cart; no slot is claimed and nothing is written.
 */
@Service
public class CheckoutPreviewService {

	public static final String PAY_AT_STORE = "PAY_AT_STORE";

	public static final String MANUAL_QR_TRANSFER = "MANUAL_QR_TRANSFER";

	private final CartRepository carts;

	private final CatalogSelectionResolver resolver;

	private final PickupSlots slots;

	private final CustomerProfiles customers;

	private final CustomerContacts contacts;

	CheckoutPreviewService(CartRepository carts, CatalogSelectionResolver resolver, PickupSlots slots,
			CustomerProfiles customers, CustomerContacts contacts) {
		this.carts = carts;
		this.resolver = resolver;
		this.slots = slots;
		this.customers = customers;
		this.contacts = contacts;
	}

	/**
	 * @param request the checkout input, already checked by the web layer
	 * @throws DomainException {@code BRANCH_NOT_FOUND}; {@code VALIDATION_FAILED} when {@code slotStart} is not the
	 * start of a slot that day
	 */
	@Transactional(readOnly = true)
	public CheckoutPreview preview(UUID userId, UUID branchId, Request request) {
		CustomerProfiles.Summary profile = this.customers.findByUser(userId)
			.orElseThrow(() -> new DomainException(CustomerErrorCode.CUSTOMER_PROFILE_NOT_FOUND,
					"User " + userId + " has no Customer profile"));
		Cart cart = this.carts.findActive(profile.customerId(), branchId).orElse(null);
		List<CartItem> items = (cart != null) ? cart.getItems() : List.of();
		int itemCount = items.stream().mapToInt(CartItem::getQuantity).sum();
		List<Issue> issues = new ArrayList<>();
		if (items.isEmpty()) {
			issues.add(new Issue("CART_EMPTY", Severity.BLOCKING, null));
		}

		// Items are priced and checked at the start of the chosen slot (AZ2, Decision AK).
		Instant at = request.scheduled()
				? ZonedDateTime.of(request.requestedDate(), request.slotStart(), BranchTime.DEFAULT_ZONE).toInstant()
				: null;
		Map<String, ResolvedLine> resolved = new HashMap<>();
		this.resolver.resolve(branchId, items.stream().map(CheckoutPreviewService::selection).toList(), at)
			.lines()
			.forEach(line -> resolved.put(line.ref(), line));
		List<Line> lines = new ArrayList<>();
		List<Issue> lineIssues = new ArrayList<>();
		for (CartItem item : items) {
			Line line = line(item, resolved.get(item.getId().toString()));
			lines.add(line);
			lineIssues.addAll(line.issues());
		}
		issues.addAll(lineIssues);

		CheckoutPreview.Fulfillment fulfillment = fulfillment(branchId, request, itemCount, issues);

		long gross = lines.stream().map(Line::grossLineTotal).filter(Objects::nonNull).mapToLong(Long::longValue).sum();
		CheckoutPreview.Totals totals = new CheckoutPreview.Totals(gross, 0, 0, 0, 0, gross);

		String method = (request.paymentMethod() != null) ? request.paymentMethod() : PAY_AT_STORE;
		PaymentOption payAtStore = new PaymentOption(PAY_AT_STORE, "NOT_REQUIRED", 0, null);
		PaymentOption payment = payAtStore;
		if (MANUAL_QR_TRANSFER.equals(method)) {
			// Not offered until the Branch's QR configuration exists (S7).
			issues.add(new Issue("PAYMENT_METHOD_NOT_AVAILABLE", Severity.BLOCKING, null));
			payment = new PaymentOption(MANUAL_QR_TRANSFER, "FULL_PREPAYMENT_REQUIRED", gross, null);
		}

		Recipient recipient = (request.recipient() != null) ? request.recipient()
				: new Recipient(profile.displayName(), this.contacts.loginPhone(userId).orElse(null));

		PricingResult result = issues.stream().anyMatch(issue -> issue.severity() == Severity.BLOCKING)
				? PricingResult.BLOCKED
				: issues.stream().anyMatch(issue -> issue.severity() == Severity.ACK_REQUIRED)
						? PricingResult.REQUIRES_ACKNOWLEDGMENT : PricingResult.VALID;
		long cartVersion = (cart != null) ? cart.getVersion() : 0;
		String fingerprint = fingerprint((cart != null) ? cart.getId() : null, cartVersion, lines, totals, fulfillment,
				recipient, method, issues);
		return new CheckoutPreview(result, fingerprint, cartVersion, lines, totals, fulfillment, recipient, payment,
				List.of(payAtStore), issues);
	}

	/**
	 * The checkout input.
	 * @param scheduled {@code SCHEDULED} timing, with {@code requestedDate} and {@code slotStart}; {@code false} for
	 * {@code ASAP}, which S4 does not offer
	 * @param recipient already checked, the phone in E.164; {@code null} for the profile's
	 * @param paymentMethod {@code null} for {@code PAY_AT_STORE}
	 */
	public record Request(boolean scheduled, LocalDate requestedDate, LocalTime slotStart, Recipient recipient,
			String paymentMethod) {
	}

	private CheckoutPreview.Fulfillment fulfillment(UUID branchId, Request request, int itemCount, List<Issue> issues) {
		if (!request.scheduled()) {
			issues.add(new Issue("FULFILLMENT_TYPE_NOT_AVAILABLE", Severity.BLOCKING, null));
			return new CheckoutPreview.Fulfillment(false, null, null, null);
		}
		LocalDate date = request.requestedDate();
		SlotDay day;
		try {
			day = this.slots
				.resolve(new SlotQuery(branchId, date, 1, OrderType.INDIVIDUAL, (itemCount > 0) ? itemCount : null))
				.days()
				.getFirst();
		}
		catch (RuleViolationsException ex) {
			boolean past = ex.violations().stream().anyMatch(violation -> "DATE_IN_PAST".equals(violation.code()));
			issues.add(new Issue(past ? "SLOT_CUTOFF_PASSED" : "BEYOND_ADVANCE_HORIZON", Severity.BLOCKING, null));
			return new CheckoutPreview.Fulfillment(false, date, request.slotStart(), null);
		}
		if (!day.open()) {
			issues.add(new Issue("BRANCH_CLOSED_ON_DATE", Severity.BLOCKING, null));
			return new CheckoutPreview.Fulfillment(false, date, request.slotStart(), null);
		}
		PickupSlot slot = day.slots()
			.stream()
			.filter(candidate -> candidate.start().equals(request.slotStart()))
			.findFirst()
			.orElseThrow(() -> new RuleViolationsException(CommonErrorCode.VALIDATION_FAILED,
					List.of(new RuleViolationsException.Violation("fulfillment.slotStart", "NOT_A_SLOT"))));
		String code = switch (slot.status()) {
			case FULL -> "SLOT_FULL";
			case CUTOFF_PASSED -> "SLOT_CUTOFF_PASSED";
			case CLOSED -> "BRANCH_CLOSED_ON_DATE";
			case AVAILABLE, NEARLY_FULL -> null;
		};
		if (code != null) {
			issues.add(new Issue(code, Severity.BLOCKING, null));
		}
		return new CheckoutPreview.Fulfillment(code == null, date, slot.start(), slot.end());
	}

	private static Line line(CartItem item, ResolvedLine resolved) {
		List<Issue> issues = new ArrayList<>();
		resolved.issues()
			.forEach(issue -> issues.add(new Issue(issue.code().name(), Severity.BLOCKING, item.getId())));
		Long unitPrice = resolved.unitPrice();
		Long previous = item.getLastSeenUnitPrice();
		PriceChange change = PriceChange.UNCHANGED;
		if (unitPrice != null && previous != null && !unitPrice.equals(previous)) {
			change = (unitPrice > previous) ? PriceChange.INCREASED : PriceChange.DECREASED;
			issues.add((change == PriceChange.INCREASED)
					? new Issue("PRICE_INCREASED", Severity.ACK_REQUIRED, item.getId())
					: new Issue("PRICE_DECREASED", Severity.INFO, item.getId()));
		}
		Long gross = resolved.lineTotal();
		return new Line(item.getId(), item.getQuantity(), unitPrice, previous, change, gross, 0, 0, gross, issues);
	}

	private static SelectionRequest selection(CartItem item) {
		return new SelectionRequest(item.getId().toString(), item.getProductId(), item.getVariantId(),
				item.getOptions().stream().map(CartItemOption::optionItemId).toList(), item.getQuantity());
	}

	/** SHA-256 hex of everything the Customer agrees to (Decision AZ5), in a fixed order. */
	private static String fingerprint(UUID cartId, long cartVersion, List<Line> lines, CheckoutPreview.Totals totals,
			CheckoutPreview.Fulfillment fulfillment, Recipient recipient, String method, List<Issue> issues) {
		StringBuilder text = new StringBuilder("checkout-v1\n");
		text.append("cart|").append(cartId).append('|').append(cartVersion).append('\n');
		for (Line line : lines) {
			text.append("line|").append(line.cartItemId()).append('|').append(line.quantity()).append('|')
				.append(line.unitPrice()).append('|').append(line.previousUnitPrice()).append('|')
				.append(String.join(",", line.issues().stream().map(Issue::code).toList())).append('\n');
		}
		text.append("totals|").append(totals.grossProductSubtotal()).append('|').append(totals.productDiscountTotal())
			.append('|').append(totals.orderDiscountTotal()).append('|').append(totals.deliveryFee()).append('|')
			.append(totals.deliveryDiscountTotal()).append('|').append(totals.grandTotal()).append('\n');
		text.append("fulfillment|").append(fulfillment.requestedDate()).append('|').append(fulfillment.slotStart())
			.append('|').append(fulfillment.ok() ? "OK" : "BLOCKED").append('\n');
		text.append("recipient|").append(recipient.name()).append('|').append(recipient.phone()).append('\n');
		text.append("payment|").append(method).append('\n');
		for (Issue issue : issues) {
			text.append("issue|").append(issue.code()).append('|').append(issue.severity()).append('|')
				.append(issue.cartItemId()).append('\n');
		}
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is always available", ex);
		}
	}

}
