package vn.hayi.shop.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.IdentityFixtures;
import vn.hayi.shop.platform.persistence.UuidBinary;

/** Owner/Admin put Products on sale, take them off and archive them (Catalog §5.2, API 08 §1.4, Decision AA). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeProductLifecycleTest {

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

	@BeforeEach
	void setUp() {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.admin = this.fixtures.openSession(this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail()));
	}

	@Test
	void activatePutsADraftProductOnSaleAndIsAudited() throws Exception {
		String id = product(category());

		MockHttpServletResponse activated = command(id, "activate", "\"v0\"");

		assertThat(activated.getStatus()).as(activated.getContentAsString()).isEqualTo(200);
		assertThat(activated.getHeader("ETag")).isEqualTo("\"v1\"");
		assertThat(JsonPath.<String>read(activated.getContentAsString(), "$.status")).isEqualTo("ACTIVE");
		List<Map<String, Object>> audits = audits(id, "PRODUCT_ACTIVATE");
		assertThat(audits).hasSize(1);
		assertThat(JsonPath.<String>read((String) audits.getFirst().get("old_value"), "$.status")).isEqualTo("DRAFT");
		assertThat(JsonPath.<String>read((String) audits.getFirst().get("new_value"), "$.status"))
			.isEqualTo("ACTIVE");
	}

	@Test
	void activationListsEveryMissingCondition() throws Exception {
		String id = product(null);
		this.jdbc.sql("UPDATE cat_product_variant SET status = 'INACTIVE' WHERE product_id = ?")
			.param(UuidBinary.toBytes(UUID.fromString(id)))
			.update();

		MockHttpServletResponse refused = command(id, "activate", null);

		assertProblem(refused, 422, "PRODUCT_ACTIVATION_INVALID");
		assertThat(JsonPath.<List<String>>read(refused.getContentAsString(), "$.issues[*].code"))
			.containsExactly("NO_ACTIVE_VARIANT", "PRIMARY_CATEGORY_MISSING");
		assertThat(status(id)).isEqualTo("DRAFT");
		assertThat(audits(id, "PRODUCT_ACTIVATE")).isEmpty();
	}

	@Test
	void aPrimaryCategoryThatIsNotActiveBlocksActivation() throws Exception {
		String category = category();
		String id = product(category);
		this.jdbc.sql("UPDATE cat_category SET status = 'INACTIVE' WHERE id = ?")
			.param(UuidBinary.toBytes(UUID.fromString(category)))
			.update();

		MockHttpServletResponse refused = command(id, "activate", null);

		assertProblem(refused, 422, "PRODUCT_ACTIVATION_INVALID");
		assertThat(JsonPath.<List<String>>read(refused.getContentAsString(), "$.issues[*].code"))
			.containsExactly("PRIMARY_CATEGORY_INACTIVE");
	}

	@Test
	void deactivateTakesItOffSaleAndActivateBringsItBack() throws Exception {
		String id = product(category());
		assertThat(command(id, "activate", null).getStatus()).isEqualTo(200);

		MockHttpServletResponse deactivated = command(id, "deactivate", "\"v1\"");
		assertThat(deactivated.getStatus()).as(deactivated.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<String>read(deactivated.getContentAsString(), "$.status")).isEqualTo("INACTIVE");
		assertThat(audits(id, "PRODUCT_DEACTIVATE")).hasSize(1);

		assertThat(command(id, "activate", null).getStatus()).isEqualTo(200);
		assertThat(status(id)).isEqualTo("ACTIVE");
	}

	@Test
	void archiveKeepsTheProductAndItsVariants() throws Exception {
		String id = product(null);

		MockHttpServletResponse archived = command(id, "archive", null);

		assertThat(archived.getStatus()).as(archived.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<String>read(archived.getContentAsString(), "$.status")).isEqualTo("ARCHIVED");
		assertThat(JsonPath.<String>read(archived.getContentAsString(), "$.archivedAt")).isNotNull();
		assertThat(JsonPath.<List<String>>read(archived.getContentAsString(), "$.variants[*].status"))
			.containsOnly("ACTIVE");
		assertThat(status(id)).isEqualTo("ARCHIVED");
		assertThat(audits(id, "PRODUCT_ARCHIVE")).hasSize(1);
	}

	@Test
	void transitionsNotAllowedFromTheCurrentStatusConflict() throws Exception {
		String id = product(category());

		assertProblem(command(id, "deactivate", null), 409, "CATALOG_PRODUCT_INVALID_TRANSITION");
		assertThat(command(id, "activate", null).getStatus()).isEqualTo(200);
		assertProblem(command(id, "activate", null), 409, "CATALOG_PRODUCT_INVALID_TRANSITION");
		assertThat(command(id, "archive", null).getStatus()).isEqualTo(200);
		assertProblem(command(id, "archive", null), 409, "CATALOG_PRODUCT_INVALID_TRANSITION");
		assertProblem(command(id, "activate", null), 409, "CATALOG_PRODUCT_INVALID_TRANSITION");
		assertProblem(command(id, "deactivate", null), 409, "CATALOG_PRODUCT_INVALID_TRANSITION");
	}

	@Test
	void aStaleIfMatchIsRejected() throws Exception {
		String id = product(category());

		assertProblem(command(id, "activate", "\"v4\""), 412, "VERSION_CONFLICT");
		assertThat(status(id)).isEqualTo("DRAFT");
	}

	@Test
	void unknownProductIsNotFound() throws Exception {
		assertProblem(command(UUID.randomUUID().toString(), "archive", null), 404, "CATALOG_PRODUCT_NOT_FOUND");
	}

	@Test
	void lifecycleNeedsCatalogManage() throws Exception {
		String id = product(null);
		UUID managerId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(managerId, anyBranch(), "MANAGER");

		assertProblem(this.auth.perform(post(PRODUCTS + "/" + id + "/archive"),
				this.fixtures.openSession(managerId), true), 403, "PERMISSION_DENIED");
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private String product(String categoryId) throws Exception {
		String code = ("life-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
		String category = (categoryId != null) ? ", \"primaryCategoryId\": \"" + categoryId + "\"" : "";
		MockHttpServletResponse created = this.auth.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"productCode": "%s", "name": "Vòng đời", "slug": "%s"%s,
					 "variants": [{"sku": "%s", "name": "Standard", "basePrice": 30000}]}
					""".formatted(code, code.toLowerCase(), category, code)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.productId");
	}

	private String category() throws Exception {
		MockHttpServletResponse created = this.auth.perform(post(CATEGORIES).contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\": \"life-" + UUID.randomUUID().toString().substring(0, 8) + "\", \"name\": \"Vòng đời\"}"),
				this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.categoryId");
	}

	private UUID anyBranch() throws Exception {
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/branches")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"branchCode": "L-%s", "name": "HaYi Test",
					 "address": {"line": "1 Lê Lợi", "province": "TP.HCM"}}
					""".formatted(UUID.randomUUID().toString().substring(0, 8))), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return UUID.fromString(JsonPath.read(created.getContentAsString(), "$.branchId"));
	}

	private MockHttpServletResponse command(String id, String action, String ifMatch) throws Exception {
		MockHttpServletRequestBuilder request = post(PRODUCTS + "/" + id + "/" + action);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return this.auth.perform(request, this.admin, true);
	}

	private String status(String id) throws Exception {
		MockHttpServletResponse detail = this.auth.perform(get(PRODUCTS + "/" + id), this.admin, false);
		assertThat(detail.getStatus()).as(detail.getContentAsString()).isEqualTo(200);
		return JsonPath.read(detail.getContentAsString(), "$.status");
	}

	private List<Map<String, Object>> audits(String id, String action) {
		return this.jdbc
			.sql("SELECT CAST(old_value AS CHAR) AS old_value, CAST(new_value AS CHAR) AS new_value"
					+ " FROM aud_audit_log WHERE entity_id = ? AND action = ?")
			.param(UuidBinary.toBytes(UUID.fromString(id)))
			.param(action)
			.query()
			.listOfRows();
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
