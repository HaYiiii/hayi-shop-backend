package vn.hayi.shop.branch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * Owner/Admin edit a Branch's information under optimistic concurrency (API 09 §1.2, API README §8, Decision O).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeBranchUpdateTest {

	static final String BRANCHES = "/api/backoffice/v1/branches";

	static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");

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

	String branchId;

	@BeforeEach
	void setUp() throws Exception {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.adminId = this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail());
		this.admin = this.fixtures.openSession(this.adminId);
		this.branchId = createBranch();
	}

	@Test
	void updateWithCurrentVersionReturnsBranchAndNewETag() throws Exception {
		MockHttpServletResponse updated = update(this.admin, "\"v0\"", """
				{"name": "HaYi Nguyễn Huệ 2", "address": {"line": "14 Nguyễn Huệ"},
				 "location": {"lat": 10.7758, "lng": 106.702}, "phone": "02838111111"}
				""");

		assertThat(updated.getStatus()).as(updated.getContentAsString()).isEqualTo(200);
		assertThat(updated.getHeader("ETag")).isEqualTo("\"v1\"");
		String body = updated.getContentAsString();
		assertThat(JsonPath.<String>read(body, "$.name")).isEqualTo("HaYi Nguyễn Huệ 2");
		assertThat(JsonPath.<String>read(body, "$.address.line")).isEqualTo("14 Nguyễn Huệ");
		assertThat(JsonPath.<String>read(body, "$.address.ward")).isEqualTo("Bến Nghé");
		assertThat(JsonPath.<String>read(body, "$.address.province")).isEqualTo("TP.HCM");
		assertThat(JsonPath.<Double>read(body, "$.location.lat")).isEqualTo(10.7758);
		assertThat(JsonPath.<String>read(body, "$.phone")).isEqualTo("02838111111");
		assertThat(JsonPath.<String>read(body, "$.email")).isEqualTo("q1@hayi.vn");
		assertThat(JsonPath.<Integer>read(body, "$.version")).isEqualTo(1);
		assertThat(detail().getHeader("ETag")).isEqualTo("\"v1\"");
	}

	@Test
	void updateRequiresIfMatch() throws Exception {
		assertProblem(update(this.admin, null, "{\"name\": \"Đổi tên\"}"), 428, "PRECONDITION_REQUIRED");

		assertThat(JsonPath.<String>read(detail().getContentAsString(), "$.name")).isEqualTo("HaYi Nguyễn Huệ");
	}

	@Test
	void updateRejectsAnyTagButTheCurrentVersion() throws Exception {
		assertProblem(update(this.admin, "\"v7\"", "{\"name\": \"Đổi tên\"}"), 412, "VERSION_CONFLICT");
		assertProblem(update(this.admin, "W/\"v0\"", "{\"name\": \"Đổi tên\"}"), 412, "VERSION_CONFLICT");
		assertProblem(update(this.admin, "*", "{\"name\": \"Đổi tên\"}"), 412, "VERSION_CONFLICT");
		assertProblem(update(this.admin, "v0", "{\"name\": \"Đổi tên\"}"), 412, "VERSION_CONFLICT");

		assertThat(detail().getHeader("ETag")).isEqualTo("\"v0\"");
	}

	@Test
	void secondEditBasedOnTheSameVersionConflicts() throws Exception {
		assertThat(update(this.admin, "\"v0\"", "{\"name\": \"Lần một\"}").getStatus()).isEqualTo(200);

		assertProblem(update(this.admin, "\"v0\"", "{\"name\": \"Lần hai\"}"), 412, "VERSION_CONFLICT");
		assertThat(JsonPath.<String>read(detail().getContentAsString(), "$.name")).isEqualTo("Lần một");
	}

	@Test
	void concurrentEditsOfOneVersionLetOnlyOneWin() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				String name = "Song song " + i;
				results.add(pool.submit(() -> {
					start.await();
					return update(this.admin, "\"v0\"", "{\"name\": \"" + name + "\"}").getStatus();
				}));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get(30, TimeUnit.SECONDS));
			}
			assertThat(statuses).containsExactlyInAnyOrder(200, 412);
		}
		assertThat(detail().getHeader("ETag")).isEqualTo("\"v1\"");
		assertThat(audits("BRANCH_UPDATE")).hasSize(1);
	}

	@Test
	void auditKeepsOldAndNewValuesOfChangedFieldsOnly() throws Exception {
		update(this.admin, "\"v0\"", "{\"name\": \"Tên mới\", \"phone\": \"02838000000\", \"email\": null}");

		List<Map<String, Object>> audits = audits("BRANCH_UPDATE");
		assertThat(audits).hasSize(1);
		Map<String, Object> audit = audits.getFirst();
		assertThat(audit).containsEntry("actor_user_id", UuidBinary.toBytes(this.adminId))
			.containsEntry("entity_type", "BRANCH");
		Map<String, Object> oldValue = JsonPath.read((String) audit.get("old_value"), "$");
		Map<String, Object> newValue = JsonPath.read((String) audit.get("new_value"), "$");
		assertThat(oldValue).containsOnlyKeys("name", "email")
			.containsEntry("name", "HaYi Nguyễn Huệ")
			.containsEntry("email", "q1@hayi.vn");
		assertThat(newValue).containsOnlyKeys("name", "email").containsEntry("name", "Tên mới").containsEntry("email",
				null);
	}

	@Test
	void editThatChangesNothingKeepsVersionAndIsNotAudited() throws Exception {
		MockHttpServletResponse same = update(this.admin, "\"v0\"",
				"{\"name\": \"HaYi Nguyễn Huệ\", \"location\": {\"lat\": 10.7757, \"lng\": 106.7019}}");

		assertThat(same.getStatus()).as(same.getContentAsString()).isEqualTo(200);
		assertThat(same.getHeader("ETag")).isEqualTo("\"v0\"");
		assertThat(audits("BRANCH_UPDATE")).isEmpty();
	}

	@Test
	void nullClearsOptionalFields() throws Exception {
		MockHttpServletResponse cleared = update(this.admin, "\"v0\"", """
				{"address": {"ward": null, "district": null}, "location": null, "phone": null, "email": null}
				""");

		assertThat(cleared.getStatus()).as(cleared.getContentAsString()).isEqualTo(200);
		String body = cleared.getContentAsString();
		assertThat(JsonPath.<Object>read(body, "$.address.ward")).isNull();
		assertThat(JsonPath.<Object>read(body, "$.address.district")).isNull();
		assertThat(JsonPath.<String>read(body, "$.address.line")).isEqualTo("12 Nguyễn Huệ");
		assertThat(JsonPath.<Object>read(body, "$.location")).isNull();
		assertThat(JsonPath.<Object>read(body, "$.phone")).isNull();
		assertThat(JsonPath.<Object>read(body, "$.email")).isNull();
	}

	@Test
	void requiredFieldsCannotBeClearedOrBlank() throws Exception {
		assertInvalid("{\"name\": null}", "name");
		assertInvalid("{\"name\": \"   \"}", "name");
		assertInvalid("{\"address\": {\"line\": null}}", "address.line");
		assertInvalid("{\"address\": {\"province\": \" \"}}", "address.province");
		assertInvalid("{\"address\": null}", "address");
		assertInvalid("{\"location\": {\"lat\": 10.7}}", "location.lng");
		assertInvalid("{\"location\": {\"lat\": 91, \"lng\": 106}}", "location.lat");
		assertInvalid("{\"name\": 12}", "name");
		assertInvalid("{\"email\": \"not-an-email\"}", "email");

		assertThat(detail().getHeader("ETag")).isEqualTo("\"v0\"");
	}

	@Test
	void branchCodeAndOtherReadOnlyKeysCannotBeChanged() throws Exception {
		assertInvalid("{\"branchCode\": \"Q1-OTHER\"}", "branchCode");
		assertInvalid("{\"status\": \"ACTIVE\"}", "status");
		assertInvalid("{\"timezone\": \"UTC\"}", "timezone");
		assertInvalid("{\"address\": {\"country\": \"VN\"}}", "address.country");
		assertProblem(update(this.admin, "\"v0\"", "[]"), 400, "VALIDATION_FAILED");

		assertThat(detail().getHeader("ETag")).isEqualTo("\"v0\"");
	}

	@Test
	void closedBranchCannotBeEdited() throws Exception {
		this.jdbc.sql("UPDATE brn_branch SET status = 'CLOSED' WHERE id = ?")
			.param(UuidBinary.toBytes(UUID.fromString(this.branchId)))
			.update();

		assertProblem(update(this.admin, "\"v0\"", "{\"name\": \"Đổi tên\"}"), 409, "BRANCH_INVALID_TRANSITION");
	}

	@Test
	void unknownBranchIsNotFound() throws Exception {
		assertProblem(this.auth.perform(patch(BRANCHES + "/" + UUID.randomUUID()).contentType(MERGE_PATCH)
			.header("If-Match", "\"v0\"")
			.content("{\"name\": \"X\"}"), this.admin, true), 404, "BRANCH_NOT_FOUND");
	}

	@Test
	void managerCannotEditBranch() throws Exception {
		UUID managerId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(managerId, UUID.fromString(this.branchId), "MANAGER");

		assertProblem(update(this.fixtures.openSession(managerId), "\"v0\"", "{\"name\": \"X\"}"), 403,
				"PERMISSION_DENIED");
	}

	private void assertInvalid(String json, String field) throws Exception {
		MockHttpServletResponse response = update(this.admin, "\"v0\"", json);
		assertProblem(response, 400, "VALIDATION_FAILED");
		assertThat(JsonPath.<List<String>>read(response.getContentAsString(), "$.errors[*].field")).as(json)
			.containsExactly(field);
	}

	private MockHttpServletResponse update(Session session, String ifMatch, String json) throws Exception {
		MockHttpServletRequestBuilder request = patch(BRANCHES + "/" + this.branchId).contentType(MERGE_PATCH)
			.content(json);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return this.auth.perform(request, session, true);
	}

	private MockHttpServletResponse detail() throws Exception {
		MockHttpServletResponse response = this.auth.perform(get(BRANCHES + "/" + this.branchId), this.admin, false);
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		return response;
	}

	private String createBranch() throws Exception {
		String code = "T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		MockHttpServletResponse created = this.auth.perform(post(BRANCHES).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"branchCode": "%s", "name": "HaYi Nguyễn Huệ",
					 "address": {"line": "12 Nguyễn Huệ", "ward": "Bến Nghé", "district": "Quận 1", "province": "TP.HCM"},
					 "location": {"lat": 10.7757, "lng": 106.7019}, "phone": "02838000000", "email": "q1@hayi.vn"}
					""".formatted(code)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.branchId");
	}

	private List<Map<String, Object>> audits(String action) {
		return this.jdbc
			.sql("SELECT entity_type, actor_user_id, CAST(old_value AS CHAR) AS old_value,"
					+ " CAST(new_value AS CHAR) AS new_value FROM aud_audit_log WHERE entity_id = ? AND action = ?")
			.param(UuidBinary.toBytes(UUID.fromString(this.branchId)))
			.param(action)
			.query()
			.listOfRows();
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
