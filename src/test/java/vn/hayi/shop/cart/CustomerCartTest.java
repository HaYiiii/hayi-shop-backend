package vn.hayi.shop.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver.Issue;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver.IssueCode;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver.ResolvedLine;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver.ResolvedOption;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver.ResolvedSelections;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver.SelectionRequest;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.error.ErrorCode;
import vn.hayi.shop.shared.error.ErrorKind;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * A signed-in Customer adds items to the server Cart of a Branch and reads it (API 03 §1, §4, Cart & Pricing
 * §6.2–§6.4, Decision F, AV). The Catalog is the {@code catalog.api} resolver, played by a small fake menu: its own
 * behaviour is tested in {@code PublicMenuTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class })
class CustomerCartTest {

	static final String PASSWORD = "ca phe sua da";

	private static final AtomicInteger NEXT = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	MutableClock clock;

	@MockitoBean
	CatalogSelectionResolver resolver;

	/** The fake menu: product → (name, size price); option item → price; what is sold out. */
	final Map<UUID, Long> products = new ConcurrentHashMap<>();

	final Map<UUID, UUID> variants = new ConcurrentHashMap<>();

	final Map<UUID, Long> options = new ConcurrentHashMap<>();

	final Set<UUID> soldOut = ConcurrentHashMap.newKeySet();

	final Set<UUID> inactiveBranches = ConcurrentHashMap.newKeySet();

	final UUID milkGroup = UuidV7.generate();

	UUID branch;

	UUID latte;

	UUID oat;

	UUID syrup;

	Customer ha;

	@BeforeEach
	void setUp() throws Exception {
		this.branch = UuidV7.generate();
		this.latte = product(50000);
		this.oat = option(10000);
		this.syrup = option(5000);
		when(this.resolver.resolve(any(), anyList(), any())).thenAnswer(call -> resolve(call.getArgument(0),
				call.getArgument(1)));
		this.ha = customer("Ngọc Hà");
	}

	// --- reading ------------------------------------------------------------------------------------------------

	@Test
	void aCustomerWithoutACartHereSeesAnEmptyOneAndNothingIsCreated() throws Exception {
		MockHttpServletResponse cart = this.ha.perform(get(cartPath()));

		assertThat(cart.getStatus()).as(cart.getContentAsString()).isEqualTo(200);
		String body = cart.getContentAsString();
		assertThat(body).contains("\"cartId\":null").contains("\"updatedAt\":null");
		assertThat(JsonPath.<Integer>read(body, "$.version")).isZero();
		assertThat(JsonPath.<List<?>>read(body, "$.items")).isEmpty();
		assertThat(JsonPath.<Integer>read(body, "$.provisionalSubtotal")).isZero();
		assertThat(JsonPath.<Boolean>read(body, "$.hasBlockingIssues")).isFalse();
		assertThat(cartRows(this.ha)).isZero();
	}

	@Test
	void anUnknownOrInactiveBranchIsNotFound() throws Exception {
		this.inactiveBranches.add(this.branch);

		assertProblem(this.ha.perform(get(cartPath())), 404, "BRANCH_NOT_FOUND");
		assertProblem(this.ha.perform(add(item(this.latte, 1, null))), 404, "BRANCH_NOT_FOUND");
	}

	// --- adding -------------------------------------------------------------------------------------------------

	@Test
	void aNewItemIsAddedWithServerPricesNamesAndThePriceSeen() throws Exception {
		MockHttpServletResponse added = this.ha.perform(add(item(this.latte, 2, "  ít   đá ", this.syrup, this.oat)));

		assertThat(added.getStatus()).as(added.getContentAsString()).isEqualTo(200);
		String body = added.getContentAsString();
		assertThat(JsonPath.<String>read(body, "$.cartId")).isNotNull();
		assertThat(JsonPath.<Integer>read(body, "$.version")).isEqualTo(1);
		assertThat(JsonPath.<String>read(body, "$.items[0].productName")).isEqualTo("Món " + this.latte);
		assertThat(JsonPath.<String>read(body, "$.items[0].variantName")).isEqualTo("L");
		assertThat(JsonPath.<String>read(body, "$.items[0].note")).isEqualTo("ít đá");
		assertThat(JsonPath.<Integer>read(body, "$.items[0].quantity")).isEqualTo(2);
		assertThat(JsonPath.<Integer>read(body, "$.items[0].unitPrice")).isEqualTo(65000);
		assertThat(JsonPath.<Integer>read(body, "$.items[0].lineTotal")).isEqualTo(130000);
		assertThat(JsonPath.<List<String>>read(body, "$.items[0].options[*].optionItemId"))
			.as("in the Catalog's order").containsExactlyElementsOf(sorted(this.oat, this.syrup));
		assertThat(JsonPath.<List<String>>read(body, "$.items[0].options[*].optionGroupName")).containsOnly("Sữa");
		assertThat(JsonPath.<List<?>>read(body, "$.items[0].issues")).isEmpty();
		assertThat(JsonPath.<Integer>read(body, "$.provisionalSubtotal")).isEqualTo(130000);
		assertThat(this.jdbc.sql("SELECT last_seen_unit_price FROM crt_cart_item").query(Long.class).list())
			.contains(65000L);
		assertThat(this.ha.perform(get(cartPath())).getContentAsString()).as("read back").isEqualTo(body);
	}

	@Test
	void anEquivalentItemAddsToTheQuantityAndADifferentNoteIsAnotherItem() throws Exception {
		this.ha.perform(add(item(this.latte, 1, "ít đá", this.oat, this.syrup)));

		MockHttpServletResponse again = this.ha.perform(add(item(this.latte, 2, " ít  đá", this.syrup, this.oat)));

		assertThat(again.getStatus()).as(again.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<List<Integer>>read(again.getContentAsString(), "$.items[*].quantity")).containsExactly(3);
		assertThat(JsonPath.<Integer>read(again.getContentAsString(), "$.version")).isEqualTo(2);
		String other = this.ha.perform(add(item(this.latte, 1, null, this.oat, this.syrup))).getContentAsString();
		assertThat(JsonPath.<List<Integer>>read(other, "$.items[*].quantity")).as("by when added")
			.containsExactly(3, 1);
		assertThat(JsonPath.<Integer>read(other, "$.provisionalSubtotal")).isEqualTo(4 * 65000);
	}

	@Test
	void anInvalidOrSoldOutItemIsRefusedWithItsIssuesAndNothingIsAdded() throws Exception {
		this.soldOut.add(this.oat);

		MockHttpServletResponse refused = this.ha.perform(add(item(this.latte, 1, null, this.oat)));

		assertProblem(refused, 422, "CART_ITEM_INVALID");
		assertThat(JsonPath.<List<String>>read(refused.getContentAsString(), "$.issues[*].code"))
			.containsExactly("OPTION_ITEM_SOLD_OUT");
		assertThat(JsonPath.<List<String>>read(refused.getContentAsString(), "$.issues[*].severity"))
			.containsExactly("BLOCKING");
		assertThat(JsonPath.<List<String>>read(refused.getContentAsString(), "$.issues[*].optionItemId"))
			.containsExactly(this.oat.toString());
		MockHttpServletResponse unknown = this.ha.perform(add(item(UuidV7.generate(), 1, null)));
		assertProblem(unknown, 422, "CART_ITEM_INVALID");
		assertThat(JsonPath.<List<String>>read(unknown.getContentAsString(), "$.issues[*].code"))
			.containsExactly("PRODUCT_NOT_FOUND");
		assertThat(cartRows(this.ha)).as("no Cart created").isZero();
	}

	@Test
	void anItemThatGoesOutAfterAddingStaysFlaggedBlocking() throws Exception {
		this.ha.perform(add(item(this.latte, 1, null, this.oat)));

		this.soldOut.add(this.oat);
		String cart = this.ha.perform(get(cartPath())).getContentAsString();

		assertThat(JsonPath.<List<?>>read(cart, "$.items")).hasSize(1);
		assertThat(JsonPath.<List<String>>read(cart, "$.items[0].issues[*].code")).containsExactly("OPTION_ITEM_SOLD_OUT");
		assertThat(JsonPath.<List<String>>read(cart, "$.items[0].issues[*].severity")).containsExactly("BLOCKING");
		assertThat(JsonPath.<List<String>>read(cart, "$.items[0].options[*].optionItemId"))
			.as("no silent substitution").containsExactly(this.oat.toString());
		assertThat(JsonPath.<Boolean>read(cart, "$.hasBlockingIssues")).isTrue();
	}

	@Test
	void anItemHoldsAtMostNinetyNine() throws Exception {
		this.ha.perform(add(item(this.latte, 60, null)));

		assertProblem(this.ha.perform(add(item(this.latte, 40, null))), 422, "CART_ITEM_QUANTITY_LIMIT");
		assertThat(JsonPath.<List<Integer>>read(this.ha.perform(get(cartPath())).getContentAsString(),
				"$.items[*].quantity")).containsExactly(60);
	}

	@Test
	void aCartHoldsAtMostFiftyDifferentItems() throws Exception {
		for (int i = 0; i < 50; i++) {
			assertThat(this.ha.perform(add(item(this.latte, 1, "món " + i))).getStatus()).isEqualTo(200);
		}

		assertProblem(this.ha.perform(add(item(this.latte, 1, "món 50"))), 422, "CART_ITEM_LIMIT");
		assertThat(this.ha.perform(add(item(this.latte, 1, "món 0"))).getStatus()).as("an equivalent one still adds")
			.isEqualTo(200);
		assertThat(JsonPath.<List<?>>read(this.ha.perform(get(cartPath())).getContentAsString(), "$.items"))
			.hasSize(50);
	}

	@Test
	void theRequestIsBoundedAndNeedsTheCsrfToken() throws Exception {
		assertProblem(this.ha.perform(add(item(this.latte, 1, "x".repeat(256)))), 400, "VALIDATION_FAILED");
		assertProblem(this.ha.perform(add(item(this.latte, 0, null))), 400, "VALIDATION_FAILED");
		assertProblem(this.ha.perform(add(item(this.latte, 100, null))), 400, "VALIDATION_FAILED");
		assertThat(this.ha.perform(add(item(this.latte, 1, " " + "x".repeat(255) + " "))).getStatus())
			.as("255 once stripped").isEqualTo(200);
		MockHttpServletResponse noCsrf = this.mvc.perform(post(cartPath() + "/items")
			.contentType(MediaType.APPLICATION_JSON)
			.content(item(this.latte, 1, null))
			.cookie(new Cookie("hy_c_at", this.ha.access))).andReturn().getResponse();
		assertProblem(noCsrf, 403, "CSRF_INVALID");
		assertThat(this.mvc.perform(get(cartPath())).andReturn().getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void theItemIsCheckedAtThePlannedPickupTime() throws Exception {
		Instant at = Instant.parse("2026-12-01T01:30:00Z");

		this.ha.perform(add(item(this.latte, 1, null).replace("}", ", \"at\": \"" + at + "\"}")));
		this.ha.perform(get(cartPath() + "?at=" + at));

		verify(this.resolver, atLeast(2)).resolve(eq(this.branch), anyList(), eq(at));
	}

	// --- concurrency and ownership ------------------------------------------------------------------------------

	@Test
	void twoFirstAddsAtOnceLeaveExactlyOneActiveCartWithBothItems() throws Exception {
		UUID mocha = product(45000);
		List<UUID> products = List.of(this.latte, mocha);

		assertAllOk(concurrently(2, i -> add(item(products.get(i), 1, null))));

		assertThat(cartRows(this.ha)).isEqualTo(1);
		assertThat(JsonPath.<List<?>>read(this.ha.perform(get(cartPath())).getContentAsString(), "$.items")).hasSize(2);
	}

	@Test
	void concurrentEquivalentAddsToAnExistingCartLoseNoQuantity() throws Exception {
		this.ha.perform(add(item(this.latte, 1, null)));

		assertAllOk(concurrently(8, i -> add(item(this.latte, 1, null))));

		assertThat(JsonPath.<List<Integer>>read(this.ha.perform(get(cartPath())).getContentAsString(),
				"$.items[*].quantity")).containsExactly(9);
	}

	@Test
	void concurrentFirstAddsOfOneNewItemToAnExistingCartMakeOneItem() throws Exception {
		this.ha.perform(add(item(this.latte, 1, "khác")));

		assertAllOk(concurrently(8, i -> add(item(this.latte, 1, null))));

		assertThat(JsonPath.<List<Integer>>read(this.ha.perform(get(cartPath())).getContentAsString(),
				"$.items[*].quantity")).containsExactly(1, 8);
	}

	@Test
	void concurrentAddsCannotPassTheQuantityLimit() throws Exception {
		this.ha.perform(add(item(this.latte, 90, null)));

		List<MockHttpServletResponse> responses = concurrently(8, i -> add(item(this.latte, 3, null)));

		assertThat(responses.stream().map(MockHttpServletResponse::getStatus))
			.containsExactlyInAnyOrder(200, 200, 200, 422, 422, 422, 422, 422);
		assertThat(JsonPath.<List<Integer>>read(this.ha.perform(get(cartPath())).getContentAsString(),
				"$.items[*].quantity")).containsExactly(99);
	}

	@Test
	void anotherCustomerNeverSeesThisCart() throws Exception {
		this.ha.perform(add(item(this.latte, 2, null)));
		Customer other = customer("Khách khác");

		String theirs = other.perform(get(cartPath())).getContentAsString();

		assertThat(theirs).contains("\"cartId\":null");
		assertThat(JsonPath.<List<?>>read(other.perform(get("/api/customer/v1/carts")).getContentAsString(), "$.items"))
			.isEmpty();
	}

	@Test
	void theCartsListShowsEachActiveCartWithItsItemCountNewestFirst() throws Exception {
		UUID firstBranch = this.branch;
		this.ha.perform(add(item(this.latte, 2, null)));
		this.ha.perform(add(item(this.latte, 1, "nóng")));
		this.branch = UuidV7.generate();
		this.clock.advance(Duration.ofSeconds(1));
		this.ha.perform(add(item(this.latte, 4, null)));

		MockHttpServletResponse list = this.ha.perform(get("/api/customer/v1/carts"));

		assertThat(list.getStatus()).as(list.getContentAsString()).isEqualTo(200);
		String body = list.getContentAsString();
		assertThat(JsonPath.<List<String>>read(body, "$.items[*].branchId"))
			.containsExactly(this.branch.toString(), firstBranch.toString());
		assertThat(JsonPath.<List<Integer>>read(body, "$.items[*].itemCount")).containsExactly(4, 3);
	}

	// --- the fake menu --------------------------------------------------------------------------------------------

	private UUID product(long price) {
		UUID id = UuidV7.generate();
		this.products.put(id, price);
		this.variants.put(id, UuidV7.generate());
		return id;
	}

	private UUID option(long price) {
		UUID id = UuidV7.generate();
		this.options.put(id, price);
		return id;
	}

	private ResolvedSelections resolve(UUID branchId, List<SelectionRequest> lines) {
		if (this.inactiveBranches.contains(branchId)) {
			throw new DomainException(TestError.BRANCH_NOT_FOUND, "inactive");
		}
		return new ResolvedSelections(lines.stream().map(this::resolve).toList(), "fp");
	}

	private ResolvedLine resolve(SelectionRequest line) {
		Long base = this.products.get(line.productId());
		if (base == null || !this.variants.get(line.productId()).equals(line.variantId())) {
			return new ResolvedLine(line.ref(), false, line.productId(), line.variantId(), List.of(), null, null, null,
					null, Map.of(), List.of(new Issue(IssueCode.PRODUCT_NOT_FOUND, null, null)), null, null, List.of());
		}
		List<UUID> normalized = line.optionItemIds()
			.stream()
			.distinct()
			.filter(this.options::containsKey)
			.sorted(Comparator.comparing(UUID::toString))
			.toList();
		List<Issue> issues = new ArrayList<>();
		Map<UUID, CatalogSelectionResolver.Availability> availability = new LinkedHashMap<>();
		List<ResolvedOption> chosen = new ArrayList<>();
		long price = base;
		for (UUID id : normalized) {
			boolean out = this.soldOut.contains(id);
			if (out) {
				issues.add(new Issue(IssueCode.OPTION_ITEM_SOLD_OUT, id, null));
			}
			availability.put(id, out ? CatalogSelectionResolver.Availability.SOLD_OUT
					: CatalogSelectionResolver.Availability.AVAILABLE);
			chosen.add(new ResolvedOption(id, this.milkGroup, "Sữa", "Tuỳ chọn", this.options.get(id)));
			price += this.options.get(id);
		}
		return new ResolvedLine(line.ref(), issues.isEmpty(), line.productId(), line.variantId(), normalized, price,
				price * line.quantity(), CatalogSelectionResolver.Availability.AVAILABLE,
				CatalogSelectionResolver.Availability.AVAILABLE, availability, issues, "Món " + line.productId(), "L",
				chosen);
	}

	private static List<String> sorted(UUID... ids) {
		return Arrays.stream(ids).map(UUID::toString).sorted().toList();
	}

	enum TestError implements ErrorCode {

		BRANCH_NOT_FOUND;

		@Override
		public ErrorKind kind() {
			return ErrorKind.NOT_FOUND;
		}

	}

	// --- helpers --------------------------------------------------------------------------------------------------

	private String cartPath() {
		return "/api/customer/v1/branches/" + this.branch + "/cart";
	}

	private MockHttpServletRequestBuilder add(String json) {
		return post(cartPath() + "/items").contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private String item(UUID productId, int quantity, String note, UUID... optionItemIds) {
		StringBuilder ids = new StringBuilder();
		for (int i = 0; i < optionItemIds.length; i++) {
			ids.append((i > 0) ? ", " : "").append('"').append(optionItemIds[i]).append('"');
		}
		UUID variant = this.variants.getOrDefault(productId, UuidV7.generate());
		return "{\"productId\": \"%s\", \"variantId\": \"%s\", \"optionItemIds\": [%s], \"quantity\": %d, \"note\": %s}"
			.formatted(productId, variant, ids, quantity, (note != null) ? "\"" + note + "\"" : "null");
	}

	/** Sends {@code count} requests of the signed-in Customer at once and waits for every response. */
	private List<MockHttpServletResponse> concurrently(int count, IntFunction<MockHttpServletRequestBuilder> request)
			throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(count);
		try {
			List<Future<MockHttpServletResponse>> calls = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				int index = i;
				calls.add(pool.submit(() -> {
					start.await();
					return this.ha.perform(request.apply(index));
				}));
			}
			start.countDown();
			List<MockHttpServletResponse> responses = new ArrayList<>();
			for (Future<MockHttpServletResponse> call : calls) {
				responses.add(call.get());
			}
			return responses;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static void assertAllOk(List<MockHttpServletResponse> responses) throws Exception {
		for (MockHttpServletResponse response : responses) {
			assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		}
	}

	private long cartRows(Customer customer) {
		return this.jdbc.sql("SELECT COUNT(*) FROM crt_cart WHERE customer_id = ? AND status = 'ACTIVE'")
			.param(UuidBinary.toBytes(customer.customerId))
			.query(Long.class)
			.single();
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

	/** A signed-in Customer: its access and CSRF cookies. */
	final class Customer {

		final UUID customerId;

		final String access;

		final String csrf;

		Customer(UUID customerId, String access, String csrf) {
			this.customerId = customerId;
			this.access = access;
			this.csrf = csrf;
		}

		MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
			return CustomerCartTest.this.mvc
				.perform(request.header("X-CSRF-Token", this.csrf)
					.cookie(new Cookie("hy_c_at", this.access), new Cookie("hy_c_csrf", this.csrf)))
				.andReturn()
				.getResponse();
		}

	}

	private Customer customer(String displayName) throws Exception {
		String national = "03" + String.format("%08d", (Instant.now().toEpochMilli() * 10 + NEXT.getAndIncrement())
				% 100_000_000L);
		String e164 = "+84" + national.substring(1);
		UUID userId = UuidV7.generate();
		UUID customerId = UuidV7.generate();
		Instant now = Instant.now();
		this.jdbc.sql("INSERT INTO iam_user (id, status, created_at, updated_at) VALUES (?, 'ACTIVE', ?, ?)")
			.params(UuidBinary.toBytes(userId), now, now)
			.update();
		this.jdbc.sql("""
				INSERT INTO iam_login_identifier (id, user_id, identifier_type, normalized_value, verified_at, created_at,
				  updated_at)
				VALUES (?, ?, 'PHONE', ?, ?, ?, ?)
				""").params(UuidBinary.toBytes(UuidV7.generate()), UuidBinary.toBytes(userId), e164, now, now, now)
			.update();
		this.jdbc.sql("""
				INSERT INTO iam_user_credential (user_id, password_hash, must_change_password, password_changed_at, updated_at)
				VALUES (?, ?, 0, ?, ?)
				""").params(UuidBinary.toBytes(userId), this.passwordEncoder.encode(PASSWORD), now, now).update();
		this.jdbc.sql("""
				INSERT INTO cus_customer_profile (id, user_id, display_name, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, 0)
				""").params(UuidBinary.toBytes(customerId), UuidBinary.toBytes(userId), displayName, now, now).update();
		MockHttpServletResponse login = this.mvc.perform(post("/api/customer/v1/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"phone\": \"%s\", \"password\": \"%s\"}".formatted(national, PASSWORD)))
			.andReturn()
			.getResponse();
		assertThat(login.getStatus()).as(login.getContentAsString()).isEqualTo(200);
		return new Customer(customerId, login.getCookie("hy_c_at").getValue(), login.getCookie("hy_c_csrf").getValue());
	}

}
