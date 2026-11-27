package vn.hayi.shop.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver.Availability;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver.Issue;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver.IssueCode;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver.ResolvedLine;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver.ResolvedSelections;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver.SelectionRequest;
import vn.hayi.shop.fulfillment.api.PickupSlot;
import vn.hayi.shop.fulfillment.api.PickupSlots;
import vn.hayi.shop.fulfillment.api.SlotCalendar;
import vn.hayi.shop.fulfillment.api.SlotDay;
import vn.hayi.shop.fulfillment.api.SlotQuery;
import vn.hayi.shop.fulfillment.api.SlotStatus;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.RuleViolationsException;
import vn.hayi.shop.shared.id.UuidV7;
import vn.hayi.shop.shared.time.BranchTime;

/**
 * Checkout preview of a PICKUP order from the server Cart (API 03 §2, Cart & Pricing §6.4–§6.7, Decision AZ). The
 * Catalog resolver and the slot calendar are fakes behind their {@code api} ports; their own behaviour is tested in
 * their modules.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, CheckoutPreviewTest.FakeSlotsConfiguration.class })
class CheckoutPreviewTest {

	private static final AtomicInteger NEXT = new AtomicInteger();

	private static final LocalDate DAY = LocalDate.now(BranchTime.DEFAULT_ZONE).plusDays(1);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PasswordEncoder passwordEncoder;

	@MockitoBean
	CatalogSelectionResolver resolver;

	@Autowired
	FakeSlots slots;

	/**
	 * The slot calendar for this test, in front of the real resolver (which other Fulfillment beans need as itself).
	 */
	@TestConfiguration
	static class FakeSlotsConfiguration {

		@Bean
		@Primary
		FakeSlots fakeSlots() {
			return new FakeSlots();
		}

	}

	static final class FakeSlots implements PickupSlots {

		volatile Function<SlotQuery, SlotCalendar> answer;

		@Override
		public SlotCalendar resolve(SlotQuery query) {
			return this.answer.apply(query);
		}

		@Override
		public SlotDay capacityOn(UUID branchId, LocalDate date) {
			throw new UnsupportedOperationException();
		}

	}

	final Map<UUID, Long> prices = new ConcurrentHashMap<>();

	final Map<UUID, UUID> variants = new ConcurrentHashMap<>();

	final Set<UUID> soldOut = ConcurrentHashMap.newKeySet();

	/** The slot calendar: one day's 09:30–10:00 slot with this status, or a closed day, or out of the horizon. */
	SlotStatus slotStatus = SlotStatus.AVAILABLE;

	boolean dayOpen = true;

	String horizonViolation;

	UUID branch;

	UUID latte;

	String phone;

	String access;

	String csrf;

	@BeforeEach
	void setUp() throws Exception {
		this.branch = UuidV7.generate();
		this.latte = UuidV7.generate();
		this.prices.put(this.latte, 50000L);
		this.variants.put(this.latte, UuidV7.generate());
		when(this.resolver.resolve(any(), anyList(), any()))
			.thenAnswer(call -> new ResolvedSelections(
					call.<List<SelectionRequest>>getArgument(1).stream().map(this::resolve).toList(), "fp"));
		this.slots.answer = this::calendar;
		signIn("Ngọc Hà");
	}

	@Test
	void aValidCartPreviewsWithServerPricesTheSlotPayAtStoreAndTheProfileAsRecipient() throws Exception {
		add(2);

		MockHttpServletResponse preview = preview(scheduled("09:30"));

		assertThat(preview.getStatus()).as(preview.getContentAsString()).isEqualTo(200);
		String body = preview.getContentAsString();
		assertThat(JsonPath.<String>read(body, "$.pricingResult")).isEqualTo("VALID");
		assertThat(JsonPath.<Integer>read(body, "$.cartVersion")).isEqualTo(1);
		assertThat(JsonPath.<Integer>read(body, "$.lines[0].unitPrice")).isEqualTo(50000);
		assertThat(JsonPath.<String>read(body, "$.lines[0].priceChange")).isEqualTo("UNCHANGED");
		assertThat(JsonPath.<Integer>read(body, "$.lines[0].netLineTotal")).isEqualTo(100000);
		assertThat(JsonPath.<Integer>read(body, "$.totals.grossProductSubtotal")).isEqualTo(100000);
		assertThat(JsonPath.<Integer>read(body, "$.totals.orderDiscountTotal")).isZero();
		assertThat(JsonPath.<Integer>read(body, "$.totals.grandTotal")).isEqualTo(100000);
		assertThat(JsonPath.<String>read(body, "$.fulfillment.status")).isEqualTo("OK");
		assertThat(JsonPath.<String>read(body, "$.fulfillment.slotEnd")).isEqualTo("10:00");
		assertThat(JsonPath.<String>read(body, "$.payment.method")).isEqualTo("PAY_AT_STORE");
		assertThat(JsonPath.<String>read(body, "$.payment.prepaymentRequirement")).isEqualTo("NOT_REQUIRED");
		assertThat(JsonPath.<List<String>>read(body, "$.availablePaymentMethods[*].method"))
			.containsExactly("PAY_AT_STORE");
		assertThat(JsonPath.<List<?>>read(body, "$.appliedPromotions")).isEmpty();
		assertThat(body).contains("\"coupon\":null");
		assertThat(JsonPath.<String>read(body, "$.recipient.name")).isEqualTo("Ngọc Hà");
		assertThat(JsonPath.<String>read(body, "$.recipient.phone")).isEqualTo(this.phone);
		assertThat(JsonPath.<String>read(body, "$.fingerprint")).hasSize(64);
		Instant slotStart = ZonedDateTime.of(DAY, LocalTime.of(9, 30), BranchTime.DEFAULT_ZONE).toInstant();
		verify(this.resolver, atLeastOnce()).resolve(eq(this.branch), anyList(), eq(slotStart));
	}

	@Test
	void aHigherPriceNeedsAcknowledgingAndALowerOneIsOnlyShown() throws Exception {
		add(1);
		this.prices.put(this.latte, 55000L);

		String higher = preview(scheduled("09:30")).getContentAsString();

		assertThat(JsonPath.<String>read(higher, "$.pricingResult")).isEqualTo("REQUIRES_ACKNOWLEDGMENT");
		assertThat(JsonPath.<Integer>read(higher, "$.lines[0].previousUnitPrice")).isEqualTo(50000);
		assertThat(JsonPath.<String>read(higher, "$.lines[0].priceChange")).isEqualTo("INCREASED");
		assertThat(JsonPath.<List<String>>read(higher, "$.issues[*].code")).containsExactly("PRICE_INCREASED");
		assertThat(JsonPath.<List<String>>read(higher, "$.issues[*].severity")).containsExactly("ACK_REQUIRED");

		this.prices.put(this.latte, 45000L);
		String lower = preview(scheduled("09:30")).getContentAsString();
		assertThat(JsonPath.<String>read(lower, "$.pricingResult")).isEqualTo("VALID");
		assertThat(JsonPath.<List<String>>read(lower, "$.lines[0].issues[*].code")).containsExactly("PRICE_DECREASED");
		assertThat(JsonPath.<List<String>>read(lower, "$.issues[*].severity")).containsExactly("INFO");
	}

	@Test
	void aSoldOutItemOrAnEmptyCartBlocks() throws Exception {
		String empty = preview(scheduled("09:30")).getContentAsString();
		assertThat(JsonPath.<String>read(empty, "$.pricingResult")).isEqualTo("BLOCKED");
		assertThat(JsonPath.<List<String>>read(empty, "$.issues[*].code")).containsExactly("CART_EMPTY");
		assertThat(JsonPath.<List<?>>read(empty, "$.lines")).isEmpty();

		add(1);
		this.soldOut.add(this.latte);
		String out = preview(scheduled("09:30")).getContentAsString();
		assertThat(JsonPath.<String>read(out, "$.pricingResult")).isEqualTo("BLOCKED");
		assertThat(JsonPath.<List<String>>read(out, "$.lines[0].issues[*].code")).containsExactly("PRODUCT_SOLD_OUT");
		assertThat(JsonPath.<List<String>>read(out, "$.issues[*].severity")).containsExactly("BLOCKING");
	}

	@Test
	void aFullPassedClosedOrTooFarSlotBlocks() throws Exception {
		add(1);

		this.slotStatus = SlotStatus.FULL;
		assertBlockedBy(preview(scheduled("09:30")), "SLOT_FULL");
		this.slotStatus = SlotStatus.CUTOFF_PASSED;
		assertBlockedBy(preview(scheduled("09:30")), "SLOT_CUTOFF_PASSED");
		this.slotStatus = SlotStatus.AVAILABLE;
		this.dayOpen = false;
		assertBlockedBy(preview(scheduled("09:30")), "BRANCH_CLOSED_ON_DATE");
		this.dayOpen = true;
		this.horizonViolation = "BEYOND_HORIZON";
		assertBlockedBy(preview(scheduled("09:30")), "BEYOND_ADVANCE_HORIZON");
		this.horizonViolation = "DATE_IN_PAST";
		assertBlockedBy(preview(scheduled("09:30")), "SLOT_CUTOFF_PASSED");
		this.horizonViolation = null;
		MockHttpServletResponse notASlot = preview(scheduled("09:45"));
		assertThat(notASlot.getStatus()).as(notASlot.getContentAsString()).isEqualTo(400);
	}

	@Test
	void theFingerprintStaysForTheSameCartAndInputAndChangesWithAnyOfThem() throws Exception {
		add(1);
		String first = fingerprint(scheduled("09:30"));

		assertThat(fingerprint(scheduled("09:30"))).isEqualTo(first);
		this.prices.put(this.latte, 52000L);
		String repriced = fingerprint(scheduled("09:30"));
		assertThat(repriced).isNotEqualTo(first);
		this.prices.put(this.latte, 50000L);
		assertThat(fingerprint(scheduled("09:30"))).isEqualTo(first);
		assertThat(fingerprint(scheduled("09:30").replace("}}", "}, \"recipient\": {\"name\": \"Minh\", \"phone\": \""
				+ this.phone + "\"}}"))).isNotEqualTo(first);
		add(1);
		assertThat(fingerprint(scheduled("09:30"))).as("the Cart changed").isNotEqualTo(first);
	}

	@Test
	void thePreviewWritesNothing() throws Exception {
		add(1);
		this.prices.put(this.latte, 55000L);
		long slotRows = this.jdbc.sql("SELECT COUNT(*) FROM ful_slot_capacity").query(Long.class).single();

		preview(scheduled("09:30"));
		preview(scheduled("09:30"));

		assertThat(this.jdbc.sql("""
				SELECT i.last_seen_unit_price FROM crt_cart_item i JOIN crt_cart c ON c.id = i.cart_id
				WHERE c.branch_id = ?
				""").param(UuidBinary.toBytes(this.branch)).query(Long.class).single()).isEqualTo(50000L);
		assertThat(this.jdbc.sql("SELECT version FROM crt_cart WHERE branch_id = ?")
			.param(UuidBinary.toBytes(this.branch))
			.query(Long.class)
			.single()).isEqualTo(1L);
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM ful_slot_capacity").query(Long.class).single())
			.isEqualTo(slotRows);
	}

	@Test
	void outsideTheS4ScopeIsBlockedOrRefused() throws Exception {
		add(1);

		String asap = preview("{\"fulfillment\": {\"type\": \"PICKUP\", \"timing\": \"ASAP\"}}").getContentAsString();
		assertThat(JsonPath.<List<String>>read(asap, "$.issues[*].code")).containsExactly("FULFILLMENT_TYPE_NOT_AVAILABLE");
		String qr = preview(scheduled("09:30").replace("}}", "}, \"paymentMethod\": \"MANUAL_QR_TRANSFER\"}"))
			.getContentAsString();
		assertThat(JsonPath.<String>read(qr, "$.pricingResult")).isEqualTo("BLOCKED");
		assertThat(JsonPath.<List<String>>read(qr, "$.issues[*].code")).containsExactly("PAYMENT_METHOD_NOT_AVAILABLE");
		assertThat(preview(scheduled("09:30").replace("}}", "}, \"couponCode\": \"HAYI10\"}")).getStatus())
			.isEqualTo(400);
		assertThat(preview(scheduled("09:30").replace("}}", "}, \"recipient\": {\"name\": \"Minh\", \"phone\": \"123\"}}"))
			.getStatus()).isEqualTo(400);
		assertThat(preview("{\"fulfillment\": {\"type\": \"PICKUP\", \"timing\": \"SCHEDULED\"}}").getStatus())
			.isEqualTo(400);
		String given = preview(scheduled("09:30").replace("}}",
				"}, \"recipient\": {\"name\": \" Minh \", \"phone\": \"0901 234 567\"}}"))
			.getContentAsString();
		assertThat(JsonPath.<String>read(given, "$.recipient.name")).isEqualTo("Minh");
		assertThat(JsonPath.<String>read(given, "$.recipient.phone")).isEqualTo("+84901234567");
		MockHttpServletResponse noCsrf = this.mvc
			.perform(post(previewPath()).contentType(MediaType.APPLICATION_JSON)
				.content(scheduled("09:30"))
				.cookie(new Cookie("hy_c_at", this.access), new Cookie("hy_c_csrf", this.csrf)))
			.andReturn()
			.getResponse();
		assertThat(noCsrf.getStatus()).isEqualTo(403);
	}

	// --- fakes ----------------------------------------------------------------------------------------------------

	private ResolvedLine resolve(SelectionRequest line) {
		Long price = this.prices.get(line.productId());
		if (price == null) {
			return new ResolvedLine(line.ref(), false, line.productId(), line.variantId(), List.of(), null, null, null,
					null, Map.of(), List.of(new Issue(IssueCode.PRODUCT_NOT_FOUND, null, null)), null, null, List.of());
		}
		boolean out = this.soldOut.contains(line.productId());
		return new ResolvedLine(line.ref(), !out, line.productId(), line.variantId(), List.of(), price,
				price * line.quantity(), out ? Availability.SOLD_OUT : Availability.AVAILABLE, Availability.AVAILABLE,
				Map.of(), out ? List.of(new Issue(IssueCode.PRODUCT_SOLD_OUT, null, null)) : List.of(),
				"Matcha Latte", "L", List.of());
	}

	private SlotCalendar calendar(SlotQuery query) {
		if (this.horizonViolation != null) {
			throw new RuleViolationsException(CommonErrorCode.VALIDATION_FAILED,
					List.of(new RuleViolationsException.Violation("days", this.horizonViolation)));
		}
		List<PickupSlot> day = this.dayOpen
				? List.of(new PickupSlot(LocalTime.of(9, 30), LocalTime.of(10, 0), this.slotStatus, 10, null, 0, 0))
				: List.of();
		return new SlotCalendar(query.branchId(), List.of(new SlotDay(query.fromDate(), this.dayOpen, day)));
	}

	// --- helpers --------------------------------------------------------------------------------------------------

	private String previewPath() {
		return "/api/customer/v1/branches/" + this.branch + "/checkout/preview";
	}

	private static String scheduled(String slotStart) {
		return "{\"fulfillment\": {\"type\": \"PICKUP\", \"timing\": \"SCHEDULED\", \"requestedDate\": \"%s\", \"slotStart\": \"%s\"}}"
			.formatted(DAY, slotStart);
	}

	private MockHttpServletResponse preview(String json) throws Exception {
		return perform(post(previewPath()).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private String fingerprint(String json) throws Exception {
		return JsonPath.read(preview(json).getContentAsString(), "$.fingerprint");
	}

	private void assertBlockedBy(MockHttpServletResponse response, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.pricingResult")).isEqualTo("BLOCKED");
		assertThat(JsonPath.<List<String>>read(response.getContentAsString(), "$.issues[*].code")).containsExactly(code);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.fulfillment.status")).isEqualTo("BLOCKED");
	}

	private void add(int quantity) throws Exception {
		MockHttpServletResponse added = perform(post("/api/customer/v1/branches/" + this.branch + "/cart/items")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"productId\": \"%s\", \"variantId\": \"%s\", \"quantity\": %d}".formatted(this.latte,
					this.variants.get(this.latte), quantity)));
		assertThat(added.getStatus()).as(added.getContentAsString()).isEqualTo(200);
	}

	private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
		return this.mvc
			.perform(request.header("X-CSRF-Token", this.csrf)
				.cookie(new Cookie("hy_c_at", this.access), new Cookie("hy_c_csrf", this.csrf)))
			.andReturn()
			.getResponse();
	}

	private void signIn(String displayName) throws Exception {
		String national = "03" + String.format("%08d", (Instant.now().toEpochMilli() * 10 + NEXT.getAndIncrement())
				% 100_000_000L);
		this.phone = "+84" + national.substring(1);
		UUID userId = UuidV7.generate();
		Instant now = Instant.now();
		this.jdbc.sql("INSERT INTO iam_user (id, status, created_at, updated_at) VALUES (?, 'ACTIVE', ?, ?)")
			.params(UuidBinary.toBytes(userId), now, now)
			.update();
		this.jdbc.sql("""
				INSERT INTO iam_login_identifier (id, user_id, identifier_type, normalized_value, verified_at, created_at,
				  updated_at)
				VALUES (?, ?, 'PHONE', ?, ?, ?, ?)
				""").params(UuidBinary.toBytes(UuidV7.generate()), UuidBinary.toBytes(userId), this.phone, now, now, now)
			.update();
		this.jdbc.sql("""
				INSERT INTO iam_user_credential (user_id, password_hash, must_change_password, password_changed_at, updated_at)
				VALUES (?, ?, 0, ?, ?)
				""").params(UuidBinary.toBytes(userId), this.passwordEncoder.encode("ca phe sua da"), now, now).update();
		this.jdbc.sql("""
				INSERT INTO cus_customer_profile (id, user_id, display_name, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, 0)
				""").params(UuidBinary.toBytes(UuidV7.generate()), UuidBinary.toBytes(userId), displayName, now, now)
			.update();
		MockHttpServletResponse login = this.mvc.perform(post("/api/customer/v1/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"phone\": \"%s\", \"password\": \"ca phe sua da\"}".formatted(national)))
			.andReturn()
			.getResponse();
		assertThat(login.getStatus()).as(login.getContentAsString()).isEqualTo(200);
		this.access = login.getCookie("hy_c_at").getValue();
		this.csrf = login.getCookie("hy_c_csrf").getValue();
	}

}
