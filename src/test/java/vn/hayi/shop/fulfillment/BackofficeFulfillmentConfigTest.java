package vn.hayi.shop.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.IdentityFixtures;
import vn.hayi.shop.platform.persistence.UuidBinary;

/**
 * Owner/Admin and the Branch's Manager configure PICKUP: slot length, horizon, cutoff, lead, capacity, cancel cutoff
 * and pickup instructions (Fulfillment §8.2–8.4, API 09 §2, Decision N, R).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeFulfillmentConfigTest {

	static final String BRANCHES = "/api/backoffice/v1/branches";

	static final String CONFIG = """
			{"isEnabled": true, "asapEnabled": false, "maxAdvanceDays": 7, "slotDurationMinutes": 30,
			 "sameDayCutoffMinutes": 180, "minLeadMinutes": 30, "groupOrderMinLeadMinutes": 120,
			 "customerCancelCutoffMinutes": 60, "defaultSlotMaxOrders": 15, "defaultSlotMaxItems": 40,
			 "pickupInstructions": "Nhận tại quầy bên trái"}
			""";

	@Autowired
	MockMvc mvc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcClient jdbc;

	BackofficeAuth auth;

	UUID adminId;

	Session admin;

	String branchId;

	@BeforeEach
	void setUp() throws Exception {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.adminId = this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail());
		this.admin = this.fixtures.openSession(this.adminId);
		this.branchId = createBranch();
	}

	@Test
	void newBranchShowsDefaultPickupConfig() throws Exception {
		MockHttpServletResponse configs = configs(this.admin);

		assertThat(configs.getStatus()).as(configs.getContentAsString()).isEqualTo(200);
		String body = configs.getContentAsString();
		assertThat(JsonPath.<List<String>>read(body, "$.items[*].fulfillmentType")).containsExactly("PICKUP");
		assertThat(JsonPath.<Boolean>read(body, "$.items[0].isEnabled")).isFalse();
		assertThat(JsonPath.<Integer>read(body, "$.items[0].maxAdvanceDays")).isEqualTo(7);
		assertThat(JsonPath.<Integer>read(body, "$.items[0].slotDurationMinutes")).isEqualTo(15);
		assertThat(JsonPath.<Integer>read(body, "$.items[0].sameDayCutoffMinutes")).isEqualTo(180);
		assertThat(JsonPath.<Integer>read(body, "$.items[0].minLeadMinutes")).isZero();
		assertThat(JsonPath.<Object>read(body, "$.items[0].defaultSlotMaxOrders")).isNull();
		assertThat(JsonPath.<Integer>read(body, "$.items[0].version")).isZero();
	}

	@Test
	void firstPutCreatesConfigAtVersionOne() throws Exception {
		MockHttpServletResponse created = replace(this.admin, "\"v0\"", CONFIG);

		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(200);
		assertThat(created.getHeader("ETag")).isEqualTo("\"v1\"");
		String body = created.getContentAsString();
		assertThat(JsonPath.<String>read(body, "$.fulfillmentType")).isEqualTo("PICKUP");
		assertThat(JsonPath.<Integer>read(body, "$.slotDurationMinutes")).isEqualTo(30);
		assertThat(JsonPath.<String>read(body, "$.pickupInstructions")).isEqualTo("Nhận tại quầy bên trái");
		assertThat(JsonPath.<Integer>read(configs(this.admin).getContentAsString(), "$.items[0].version"))
			.isEqualTo(1);
		assertThat(audits()).hasSize(1);
	}

	@Test
	void putUpdatesConfigAndAuditsChangedFields() throws Exception {
		replace(this.admin, "\"v0\"", CONFIG);

		MockHttpServletResponse updated = replace(this.admin, "\"v1\"",
				CONFIG.replace("\"minLeadMinutes\": 30", "\"minLeadMinutes\": 45")
					.replace("\"defaultSlotMaxItems\": 40", "\"defaultSlotMaxItems\": null"));

		assertThat(updated.getStatus()).as(updated.getContentAsString()).isEqualTo(200);
		assertThat(updated.getHeader("ETag")).isEqualTo("\"v2\"");
		assertThat(JsonPath.<Object>read(updated.getContentAsString(), "$.defaultSlotMaxItems")).isNull();
		List<Map<String, Object>> audits = audits();
		assertThat(audits).hasSize(2);
		Map<String, Object> oldValue = JsonPath.read((String) audits.getLast().get("old_value"), "$");
		Map<String, Object> newValue = JsonPath.read((String) audits.getLast().get("new_value"), "$");
		assertThat(oldValue).containsOnlyKeys("minLeadMinutes", "defaultSlotMaxItems")
			.containsEntry("minLeadMinutes", 30)
			.containsEntry("defaultSlotMaxItems", 40);
		assertThat(newValue).containsEntry("minLeadMinutes", 45).containsEntry("defaultSlotMaxItems", null);
	}

	@Test
	void identicalPutKeepsVersionAndIsNotAudited() throws Exception {
		replace(this.admin, "\"v0\"", CONFIG);

		MockHttpServletResponse same = replace(this.admin, "\"v1\"", CONFIG);

		assertThat(same.getStatus()).as(same.getContentAsString()).isEqualTo(200);
		assertThat(same.getHeader("ETag")).isEqualTo("\"v1\"");
		assertThat(audits()).hasSize(1);
	}

	@Test
	void putRequiresCurrentIfMatch() throws Exception {
		assertProblem(replace(this.admin, null, CONFIG), 428, "PRECONDITION_REQUIRED");
		assertProblem(replace(this.admin, "\"v1\"", CONFIG), 412, "VERSION_CONFLICT");
		replace(this.admin, "\"v0\"", CONFIG);

		// A client still holding the default's "v0" cannot overwrite the saved config (Decision R2).
		assertProblem(replace(this.admin, "\"v0\"", CONFIG.replace("\"maxAdvanceDays\": 7", "\"maxAdvanceDays\": 3")),
				412, "VERSION_CONFLICT");
	}

	@Test
	void concurrentFirstPutsLetOnlyOneWin() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				String json = CONFIG.replace("\"maxAdvanceDays\": 7", "\"maxAdvanceDays\": " + (3 + i));
				results.add(pool.submit(() -> {
					start.await();
					return replace(this.admin, "\"v0\"", json).getStatus();
				}));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get(30, TimeUnit.SECONDS));
			}
			assertThat(statuses).containsExactlyInAnyOrder(200, 412);
		}
		assertThat(audits()).hasSize(1);
	}

	@Test
	void invalidValuesAreRejectedWithTheirField() throws Exception {
		MockHttpServletResponse invalid = replace(this.admin, "\"v0\"", """
				{"isEnabled": true, "asapEnabled": false, "maxAdvanceDays": -1, "slotDurationMinutes": 20,
				 "sameDayCutoffMinutes": -5, "minLeadMinutes": -1, "groupOrderMinLeadMinutes": -1,
				 "customerCancelCutoffMinutes": -1, "defaultSlotMaxOrders": 0, "defaultSlotMaxItems": 0,
				 "pickupInstructions": "%s"}
				""".formatted("x".repeat(501)));

		assertProblem(invalid, 422, "FULFILLMENT_CONFIG_INVALID");
		assertThat(JsonPath.<List<String>>read(invalid.getContentAsString(), "$.errors[*].field")).containsExactly(
				"maxAdvanceDays", "slotDurationMinutes", "sameDayCutoffMinutes", "minLeadMinutes",
				"groupOrderMinLeadMinutes", "customerCancelCutoffMinutes", "defaultSlotMaxOrders",
				"defaultSlotMaxItems", "pickupInstructions");
		assertThat(JsonPath.<Integer>read(configs(this.admin).getContentAsString(), "$.items[0].version")).isZero();
	}

	@Test
	void malformedBodyIsAValidationError() throws Exception {
		assertProblem(replace(this.admin, "\"v0\"", CONFIG.replace("\"isEnabled\": true, ", "")), 400,
				"VALIDATION_FAILED");
		assertProblem(replace(this.admin, "\"v0\"", CONFIG.replace("\"maxAdvanceDays\": 7", "\"maxAdvanceDays\": \"x\"")),
				400, "VALIDATION_FAILED");
	}

	@Test
	void onlyPickupIsAccepted() throws Exception {
		for (String type : List.of("DELIVERY", "DINE_IN", "pickup", "FOO")) {
			MockHttpServletResponse response = this.auth.perform(put(path(type)).contentType(MediaType.APPLICATION_JSON)
				.header("If-Match", "\"v0\"")
				.content(CONFIG), this.admin, true);
			assertProblem(response, 422, "FULFILLMENT_CONFIG_INVALID");
			assertThat(JsonPath.<List<String>>read(response.getContentAsString(), "$.errors[*].field")).as(type)
				.containsExactly("fulfillmentType");
		}
	}

	@Test
	void unknownBranchIsNotFound() throws Exception {
		this.branchId = UUID.randomUUID().toString();

		assertProblem(configs(this.admin), 404, "BRANCH_NOT_FOUND");
		assertProblem(replace(this.admin, "\"v0\"", CONFIG), 404, "BRANCH_NOT_FOUND");
	}

	@Test
	void closedBranchConfigIsLocked() throws Exception {
		this.jdbc.sql("UPDATE brn_branch SET status = 'CLOSED' WHERE id = ?")
			.param(UuidBinary.toBytes(UUID.fromString(this.branchId)))
			.update();

		assertThat(configs(this.admin).getStatus()).isEqualTo(200);
		assertProblem(replace(this.admin, "\"v0\"", CONFIG), 409, "BRANCH_INVALID_TRANSITION");
	}

	@Test
	void managerConfiguresOnlyTheAssignedBranch() throws Exception {
		String other = createBranch();
		UUID managerId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(managerId, UUID.fromString(this.branchId), "MANAGER");
		Session manager = this.fixtures.openSession(managerId);

		MockHttpServletResponse own = replace(manager, "\"v0\"", CONFIG);
		assertThat(own.getStatus()).as(own.getContentAsString()).isEqualTo(200);

		this.branchId = other;
		assertProblem(replace(manager, "\"v0\"", CONFIG), 403, "BRANCH_SCOPE_DENIED");
	}

	private MockHttpServletResponse configs(Session session) throws Exception {
		return this.auth.perform(get(BRANCHES + "/" + this.branchId + "/fulfillment/configs"), session, false);
	}

	private MockHttpServletResponse replace(Session session, String ifMatch, String json) throws Exception {
		MockHttpServletRequestBuilder request = put(path("PICKUP")).contentType(MediaType.APPLICATION_JSON)
			.content(json);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return this.auth.perform(request, session, true);
	}

	private String path(String type) {
		return BRANCHES + "/" + this.branchId + "/fulfillment/configs/" + type;
	}

	private List<Map<String, Object>> audits() {
		return this.jdbc
			.sql("SELECT CAST(old_value AS CHAR) AS old_value, CAST(new_value AS CHAR) AS new_value FROM aud_audit_log"
					+ " WHERE branch_id = ? AND action = 'FULFILLMENT_CONFIG_UPDATE' ORDER BY occurred_at, id")
			.param(UuidBinary.toBytes(UUID.fromString(this.branchId)))
			.query()
			.listOfRows();
	}

	private String createBranch() throws Exception {
		String code = "T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		MockHttpServletResponse created = this.auth.perform(post(BRANCHES).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"branchCode": "%s", "name": "HaYi test", "address": {"line": "1 Lê Lợi", "province": "TP.HCM"}}
					""".formatted(code)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.branchId");
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
