package vn.hayi.shop.branch;

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

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.IdentityFixtures;
import vn.hayi.shop.platform.persistence.UuidBinary;

/**
 * Owner/Admin create Branches and everyone with {@code BRANCH_VIEW} reads those in their scope (Branch §4.1–4.3,
 * API 09 §1, Decision M1, M2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeBranchTest {

	static final String BRANCHES = "/api/backoffice/v1/branches";

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

	@Test
	void ownerCreatesDraftBranch() throws Exception {
		String code = uniqueCode();

		MockHttpServletResponse created = create(this.admin, """
				{"branchCode": "%s", "name": "HaYi Nguyễn Huệ",
				 "address": {"line": "12 Nguyễn Huệ", "ward": "Bến Nghé", "district": "Quận 1", "province": "TP.HCM"},
				 "location": {"lat": 10.7757, "lng": 106.7019}, "phone": "02838000000", "email": "q1@hayi.vn"}
				""".formatted(code));

		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		assertThat(created.getHeader("ETag")).isEqualTo("\"v0\"");
		String body = created.getContentAsString();
		String branchId = JsonPath.read(body, "$.branchId");
		assertThat(created.getHeader("Location")).endsWith(BRANCHES + "/" + branchId);
		assertThat(JsonPath.<String>read(body, "$.branchCode")).isEqualTo(code);
		assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("DRAFT");
		assertThat(JsonPath.<String>read(body, "$.address.province")).isEqualTo("TP.HCM");
		assertThat(JsonPath.<Double>read(body, "$.location.lat")).isEqualTo(10.7757);
		assertThat(JsonPath.<String>read(body, "$.timezone")).isEqualTo("Asia/Ho_Chi_Minh");
		assertThat(JsonPath.<Object>read(body, "$.activatedAt")).isNull();
		assertThat(JsonPath.<Integer>read(body, "$.version")).isZero();
		assertThat(auditOf(UUID.fromString(branchId))).containsEntry("action", "BRANCH_CREATE")
			.containsEntry("entity_type", "BRANCH")
			.containsEntry("actor_user_id", UuidBinary.toBytes(this.adminId));
	}

	@Test
	void branchCodeIsTakenWhateverItsCase() throws Exception {
		String code = uniqueCode();
		assertThat(create(this.admin, minimal(code)).getStatus()).isEqualTo(201);

		MockHttpServletResponse again = create(this.admin, minimal(code.toLowerCase()));

		assertProblem(again, 409, "BRANCH_CODE_TAKEN");
	}

	@Test
	void createNeedsNameAndWellFormedCode() throws Exception {
		assertProblem(create(this.admin, """
				{"branchCode": "%s", "address": {"line": "1 Lê Lợi", "province": "TP.HCM"}}
				""".formatted(uniqueCode())), 400, "VALIDATION_FAILED");
		assertProblem(create(this.admin, minimal("-BAD CODE")), 400, "VALIDATION_FAILED");
	}

	@Test
	void managerCannotCreateBranch() throws Exception {
		UUID managerId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(managerId, UUID.randomUUID(), "MANAGER");

		assertProblem(create(this.fixtures.openSession(managerId), minimal(uniqueCode())), 403, "PERMISSION_DENIED");
	}

	@Test
	void branchDetailCarriesETag() throws Exception {
		String code = uniqueCode();
		String branchId = createdId(code);

		MockHttpServletResponse detail = detail(this.admin, branchId);

		assertThat(detail.getStatus()).as(detail.getContentAsString()).isEqualTo(200);
		assertThat(detail.getHeader("ETag")).isEqualTo("\"v0\"");
		assertThat(JsonPath.<String>read(detail.getContentAsString(), "$.branchCode")).isEqualTo(code);
		assertThat(JsonPath.<Object>read(detail.getContentAsString(), "$.location")).isNull();
	}

	@Test
	void unknownBranchIsNotFound() throws Exception {
		assertProblem(detail(this.admin, UUID.randomUUID().toString()), 404, "BRANCH_NOT_FOUND");
	}

	@Test
	void managerReadsOnlyAssignedBranch() throws Exception {
		String assigned = createdId(uniqueCode());
		String other = createdId(uniqueCode());
		UUID managerId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(managerId, UUID.fromString(assigned), "MANAGER");
		Session manager = this.fixtures.openSession(managerId);

		assertThat(detail(manager, assigned).getStatus()).isEqualTo(200);
		assertProblem(detail(manager, other), 403, "BRANCH_SCOPE_DENIED");
	}

	@Test
	void storeAdminListsEveryBranchSortedByCode() throws Exception {
		String second = createdId("T-ZZ" + uniqueCode().substring(2));
		String first = createdId("T-AA" + uniqueCode().substring(2));

		String body = list(this.admin, "").getContentAsString();

		assertThat(JsonPath.<List<String>>read(body, "$.items[*].branchId")).contains(first, second);
		List<String> codes = JsonPath.read(body, "$.items[*].branchCode");
		assertThat(codes).isSorted();
	}

	@Test
	void managerListsOnlyAssignedBranches() throws Exception {
		String assigned = createdId(uniqueCode());
		createdId(uniqueCode());
		UUID managerId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(managerId, UUID.fromString(assigned), "MANAGER");

		String body = list(this.fixtures.openSession(managerId), "").getContentAsString();

		assertThat(JsonPath.<List<String>>read(body, "$.items[*].branchId")).containsExactly(assigned);
	}

	@Test
	void listFiltersByStatus() throws Exception {
		String draft = createdId(uniqueCode());

		assertThat(JsonPath.<List<String>>read(list(this.admin, "?status=DRAFT").getContentAsString(),
				"$.items[*].branchId")).contains(draft);
		assertThat(JsonPath.<List<String>>read(list(this.admin, "?status=ACTIVE,CLOSED").getContentAsString(),
				"$.items[*].branchId")).doesNotContain(draft);
	}

	private MockHttpServletResponse list(Session session, String query) throws Exception {
		MockHttpServletResponse response = this.auth.perform(get(BRANCHES + query), session, false);
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		return response;
	}

	private String createdId(String code) throws Exception {
		MockHttpServletResponse created = create(this.admin, minimal(code));
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.branchId");
	}

	private MockHttpServletResponse detail(Session session, String branchId) throws Exception {
		return this.auth.perform(get(BRANCHES + "/" + branchId), session, false);
	}

	private MockHttpServletResponse create(Session session, String json) throws Exception {
		return this.auth.perform(post(BRANCHES).contentType(MediaType.APPLICATION_JSON).content(json), session, true);
	}

	private Map<String, Object> auditOf(UUID branchId) {
		return this.jdbc.sql("SELECT action, entity_type, actor_user_id FROM aud_audit_log WHERE entity_id = ?")
			.param(UuidBinary.toBytes(branchId))
			.query()
			.singleRow();
	}

	private static String minimal(String code) {
		return """
				{"branchCode": "%s", "name": "HaYi test", "address": {"line": "1 Lê Lợi", "province": "TP.HCM"}}
				""".formatted(code);
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

	/** Branch codes are never reused and the database is shared by every test. */
	private static String uniqueCode() {
		return "T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
	}

}
