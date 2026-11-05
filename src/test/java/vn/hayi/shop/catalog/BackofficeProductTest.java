package vn.hayi.shop.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.IdentityFixtures;
import vn.hayi.shop.platform.persistence.UuidBinary;

/**
 * Owner/Admin create DRAFT Products with their Variants and browse them (Catalog §5.2, API 08 §1.3, Decision Z).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeProductTest {

	static final String PRODUCTS = "/api/backoffice/v1/products";

	static final String CATEGORIES = "/api/backoffice/v1/categories";

	@Autowired
	MockMvc mvc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcClient jdbc;

	BackofficeAuth auth;

	Session admin;

	UUID adminId;

	@BeforeEach
	void setUp() {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.adminId = this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail());
		this.admin = this.fixtures.openSession(this.adminId);
	}

	// --- create -------------------------------------------------------------------------------------------------

	@Test
	void createsADraftProductWithItsVariants() throws Exception {
		String code = code("matcha");
		String category = category();
		MockHttpServletResponse created = create("""
				{"productCode": "%s", "name": "Matcha Latte", "slug": "%s", "primaryCategoryId": "%s",
				 "shortDescription": "Ngọt nhẹ", "description": "Matcha Uji",
				 "variants": [
				   {"sku": "%s-m", "name": "M", "basePrice": 45000, "isDefault": true},
				   {"sku": "%s-l", "name": "L", "basePrice": 55000, "isDefault": false}]}
				""".formatted(code.toLowerCase(), slug("matcha-latte"), category, code.toLowerCase(),
				code.toLowerCase()));

		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		assertThat(created.getHeader("ETag")).isEqualTo("\"v0\"");
		String body = created.getContentAsString();
		String id = JsonPath.read(body, "$.productId");
		assertThat(created.getHeader("Location")).isEqualTo(PRODUCTS + "/" + id);
		assertThat(JsonPath.<String>read(body, "$.productCode")).as("upper-cased").isEqualTo(code);
		assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("DRAFT");
		assertThat(JsonPath.<String>read(body, "$.productType")).isEqualTo("STANDARD");
		assertThat(JsonPath.<String>read(body, "$.primaryCategoryId")).isEqualTo(category);
		assertThat(JsonPath.<List<String>>read(body, "$.variants[*].sku")).containsExactly(code + "-M", code + "-L");
		assertThat(JsonPath.<List<Integer>>read(body, "$.variants[*].basePrice")).containsExactly(45000, 55000);
		assertThat(JsonPath.<List<Boolean>>read(body, "$.variants[*].isDefault")).containsExactly(true, false);
		assertThat(JsonPath.<List<String>>read(body, "$.variants[*].status")).containsOnly("ACTIVE");
		assertThat(JsonPath.<List<Integer>>read(body, "$.variants[*].sortOrder")).containsExactly(0, 1);
		assertThat(JsonPath.<Object>read(body, "$.archivedAt")).isNull();
	}

	@Test
	void aSingleVariantIsTheDefault() throws Exception {
		MockHttpServletResponse created = create(simple(code("tra"), slug("tra"), 30000));

		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		assertThat(JsonPath.<List<Boolean>>read(created.getContentAsString(), "$.variants[*].isDefault"))
			.containsExactly(true);
	}

	@Test
	void severalVariantsNeedExactlyOneDefault() throws Exception {
		String code = code("hai");
		assertInvalid(create(twoVariants(code, false, false)), "variants");
		assertInvalid(create(twoVariants(code, true, true)), "variants");
		assertInvalid(create(twoVariantsWithoutFlags(code)), "variants");
	}

	@Test
	void aSkuTwiceInOneRequestIsInvalid() throws Exception {
		String code = code("lap");
		assertInvalid(create("""
				{"productCode": "%s", "name": "Lặp", "slug": "%s",
				 "variants": [{"sku": "%s-x", "name": "M", "basePrice": 1, "isDefault": true},
				              {"sku": "%s-X", "name": "L", "basePrice": 2, "isDefault": false}]}
				""".formatted(code, slug("lap"), code, code)), "variants[1].sku");
	}

	@Test
	void comboIsNotSupportedYet() throws Exception {
		String code = code("combo");
		assertProblem(create("""
				{"productCode": "%s", "name": "Combo", "slug": "%s", "productType": "COMBO",
				 "variants": [{"sku": "%s", "name": "Standard", "basePrice": 70000}]}
				""".formatted(code, slug("combo"), code)), 422, "PRODUCT_TYPE_NOT_SUPPORTED");
	}

	@Test
	void codeSlugAndSkuAreUniqueEvenAcrossCase() throws Exception {
		String code = code("dup");
		String slug = slug("dup");
		assertThat(create(simple(code, slug, 10000)).getStatus()).isEqualTo(201);

		assertProblem(create(simple(code.toLowerCase(), slug("other"), 10000, code("sku"))), 409,
				"CATALOG_CODE_TAKEN");
		assertProblem(create(simple(code("other"), slug, 10000)), 409, "CATALOG_SLUG_TAKEN");
		assertProblem(create(simple(code("other"), slug("other"), 10000, code.toLowerCase())), 409,
				"CATALOG_SKU_TAKEN");
	}

	@Test
	void concurrentCreatesOfOneCodeLetOnlyOneWin() throws Exception {
		String code = code("race");
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				String slug = slug("race");
				String sku = code("race-sku");
				results.add(pool.submit(() -> {
					start.await();
					return create(simple(code, slug, 10000, sku)).getStatus();
				}));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get(30, TimeUnit.SECONDS));
			}
			assertThat(statuses).containsExactlyInAnyOrder(201, 409);
		}
	}

	@Test
	void requestShapeIsValidated() throws Exception {
		assertInvalid(create(simple("-bad", slug("x"), 1)), "productCode");
		assertInvalid(create(simple(code("x"), "Not Kebab", 1)), "slug");
		assertInvalid(create(simple(code("x"), slug("x"), -1)), "variants[0].basePrice");
		assertInvalid(create("""
				{"productCode": "%s", "name": "Rỗng", "slug": "%s", "variants": []}
				""".formatted(code("x"), slug("x"))), "variants");
	}

	@Test
	void primaryCategoryMustExistAndBeActive() throws Exception {
		assertProblem(create(withCategory(UUID.randomUUID().toString())), 404, "CATALOG_CATEGORY_NOT_FOUND");

		String archived = category();
		assertThat(this.auth.perform(post(CATEGORIES + "/" + archived + "/archive"), this.admin, true).getStatus())
			.isEqualTo(200);
		assertProblem(create(withCategory(archived)), 409, "CATALOG_CATEGORY_ARCHIVED");
	}

	@Test
	void createIsAudited() throws Exception {
		String code = code("audit");
		String id = createdId(simple(code, slug("audit"), 25000));

		List<Map<String, Object>> audits = this.jdbc
			.sql("SELECT entity_type, actor_user_id, CAST(new_value AS CHAR) AS new_value FROM aud_audit_log"
					+ " WHERE entity_id = ? AND action = 'PRODUCT_CREATE'")
			.param(UuidBinary.toBytes(UUID.fromString(id)))
			.query()
			.listOfRows();
		assertThat(audits).hasSize(1);
		assertThat(audits.getFirst()).containsEntry("entity_type", "PRODUCT")
			.containsEntry("actor_user_id", UuidBinary.toBytes(this.adminId));
		String newValue = (String) audits.getFirst().get("new_value");
		assertThat(JsonPath.<String>read(newValue, "$.productCode")).isEqualTo(code);
		assertThat(JsonPath.<List<Integer>>read(newValue, "$.variants[*].basePrice")).containsExactly(25000);
	}

	@Test
	void creatingNeedsCatalogManageAndPriceManage() throws Exception {
		UUID managerId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(managerId, anyBranch(), "MANAGER");
		Session manager = this.fixtures.openSession(managerId);

		assertProblem(this.auth.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON)
			.content(simple(code("no"), slug("no"), 1)), manager, true), 403, "PERMISSION_DENIED");
		assertThat(this.auth.perform(get(PRODUCTS), manager, false).getStatus()).as("CATALOG_VIEW").isEqualTo(200);
	}

	// --- read ---------------------------------------------------------------------------------------------------

	@Test
	void detailReturnsTheProductWithItsVariantsAndETag() throws Exception {
		String code = code("chi-tiet");
		String id = createdId(twoVariants(code, true, false));

		MockHttpServletResponse detail = this.auth.perform(get(PRODUCTS + "/" + id), this.admin, false);

		assertThat(detail.getStatus()).as(detail.getContentAsString()).isEqualTo(200);
		assertThat(detail.getHeader("ETag")).isEqualTo("\"v0\"");
		assertThat(JsonPath.<String>read(detail.getContentAsString(), "$.productCode")).isEqualTo(code);
		assertThat(JsonPath.<List<String>>read(detail.getContentAsString(), "$.variants[*].name"))
			.containsExactly("M", "L");
	}

	@Test
	void unknownProductIsNotFound() throws Exception {
		assertProblem(this.auth.perform(get(PRODUCTS + "/" + UUID.randomUUID()), this.admin, false), 404,
				"CATALOG_PRODUCT_NOT_FOUND");
	}

	@Test
	void listFiltersByCategoryStatusAndQueryWithOffsetPaging() throws Exception {
		String category = category();
		String marker = UUID.randomUUID().toString().substring(0, 6);
		String banana = createdId(withCategory(category, "Bánh chuối " + marker, code("b")));
		String apple = createdId(withCategory(category, "Bánh táo " + marker, code("a")));
		String cake = createdId(withCategory(category, "Cà phê " + marker, code("c")));
		this.jdbc.sql("UPDATE cat_product SET status = 'ACTIVE' WHERE id = ?")
			.param(UuidBinary.toBytes(UUID.fromString(cake)))
			.update();

		String page0 = list("categoryId", category, "size", "2", "page", "0");
		assertThat(JsonPath.<List<String>>read(page0, "$.items[*].productId")).as("sorted by name")
			.containsExactly(banana, apple);
		assertThat(JsonPath.<Integer>read(page0, "$.page.totalItems")).isEqualTo(3);
		assertThat(JsonPath.<Integer>read(page0, "$.page.totalPages")).isEqualTo(2);
		assertThat(JsonPath.<Integer>read(page0, "$.page.size")).isEqualTo(2);
		String page1 = list("categoryId", category, "size", "2", "page", "1");
		assertThat(JsonPath.<List<String>>read(page1, "$.items[*].productId")).containsExactly(cake);

		assertThat(JsonPath.<List<String>>read(list("categoryId", category, "status", "ACTIVE"),
				"$.items[*].productId")).containsExactly(cake);
		assertThat(JsonPath.<List<String>>read(list("q", "BÁNH TÁO " + marker),
				"$.items[*].productId")).as("q on name, any case").containsExactly(apple);
	}

	@Test
	void listMatchesCodeAndSkuAndSummarisesVariants() throws Exception {
		String code = code("tim");
		String id = createdId(twoVariants(code, false, true));

		String byCode = list("q", code.toLowerCase());
		assertThat(JsonPath.<List<String>>read(byCode, "$.items[*].productId")).containsExactly(id);
		Map<String, Object> item = JsonPath.<List<Map<String, Object>>>read(byCode, "$.items").getFirst();
		assertThat(item).containsEntry("fromPrice", 45000).containsEntry("variantCount", 2)
			.containsEntry("status", "DRAFT");
		assertThat(JsonPath.<List<String>>read(list("q", code + "-L"), "$.items[*].productId")).as("sku")
			.containsExactly(id);
		assertThat(JsonPath.<List<String>>read(list("q", "%" + code), "$.items[*].productId"))
			.as("% is literal").isEmpty();
	}

	@Test
	void listRejectsAnOversizedPage() throws Exception {
		assertProblem(this.auth.perform(get(PRODUCTS).param("size", "101"), this.admin, false), 400,
				"VALIDATION_FAILED");
	}

	// --- Category archive -------------------------------------------------------------------------------------------

	@Test
	void categoryUsedAsPrimaryByAProductCannotBeArchived() throws Exception {
		String category = category();
		String id = createdId(withCategory(category));

		assertProblem(this.auth.perform(post(CATEGORIES + "/" + category + "/archive"), this.admin, true), 409,
				"CATALOG_CATEGORY_IN_USE");

		this.jdbc.sql("UPDATE cat_product SET status = 'ARCHIVED' WHERE id = ?")
			.param(UuidBinary.toBytes(UUID.fromString(id)))
			.update();
		assertThat(this.auth.perform(post(CATEGORIES + "/" + category + "/archive"), this.admin, true).getStatus())
			.as("archived Products do not count")
			.isEqualTo(200);
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private String category() throws Exception {
		MockHttpServletResponse created = this.auth.perform(post(CATEGORIES).contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\": \"" + slug("danh-muc") + "\", \"name\": \"Danh mục\"}"), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.categoryId");
	}

	private UUID anyBranch() throws Exception {
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/branches")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"branchCode": "P-%s", "name": "HaYi Test",
					 "address": {"line": "1 Lê Lợi", "province": "TP.HCM"}}
					""".formatted(UUID.randomUUID().toString().substring(0, 8))), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return UUID.fromString(JsonPath.read(created.getContentAsString(), "$.branchId"));
	}

	private static String code(String prefix) {
		return (prefix + "-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
	}

	private static String slug(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private static String simple(String code, String slug, long price) {
		return simple(code, slug, price, code);
	}

	private static String simple(String code, String slug, long price, String sku) {
		return """
				{"productCode": "%s", "name": "Món %s", "slug": "%s",
				 "variants": [{"sku": "%s", "name": "Standard", "basePrice": %d}]}
				""".formatted(code, code, slug, sku, price);
	}

	private static String twoVariants(String code, boolean firstDefault, boolean secondDefault) {
		return """
				{"productCode": "%s", "name": "Hai size %s", "slug": "%s",
				 "variants": [{"sku": "%s-M", "name": "M", "basePrice": 45000, "isDefault": %s},
				              {"sku": "%s-L", "name": "L", "basePrice": 55000, "isDefault": %s}]}
				""".formatted(code, code, slug("hai-size"), code, firstDefault, code, secondDefault);
	}

	private static String twoVariantsWithoutFlags(String code) {
		return """
				{"productCode": "%s", "name": "Hai size", "slug": "%s",
				 "variants": [{"sku": "%s-M", "name": "M", "basePrice": 1}, {"sku": "%s-L", "name": "L", "basePrice": 2}]}
				""".formatted(code, slug("hai-size"), code, code);
	}

	private static String withCategory(String categoryId) {
		return withCategory(categoryId, "Có danh mục", code("cat"));
	}

	private static String withCategory(String categoryId, String name, String code) {
		return """
				{"productCode": "%s", "name": "%s", "slug": "%s", "primaryCategoryId": "%s",
				 "variants": [{"sku": "%s", "name": "Standard", "basePrice": 30000}]}
				""".formatted(code, name, slug("co-danh-muc"), categoryId, code);
	}

	private MockHttpServletResponse create(String json) throws Exception {
		return this.auth.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON).content(json), this.admin,
				true);
	}

	private String createdId(String json) throws Exception {
		MockHttpServletResponse created = create(json);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.productId");
	}

	/** {@code params} as name/value pairs, sent unencoded so MockMvc encodes them once. */
	private String list(String... params) throws Exception {
		var request = get(PRODUCTS);
		for (int i = 0; i < params.length; i += 2) {
			request.param(params[i], params[i + 1]);
		}
		MockHttpServletResponse response = this.auth.perform(request, this.admin, false);
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		return response.getContentAsString();
	}

	private static void assertInvalid(MockHttpServletResponse response, String field) throws Exception {
		assertProblem(response, 400, "VALIDATION_FAILED");
		assertThat(JsonPath.<List<String>>read(response.getContentAsString(), "$.errors[*].field"))
			.as(response.getContentAsString())
			.contains(field);
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
