package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.infrastructure.RoleQueries;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * Owners and Admins grant and revoke roles (API 10 §2, API 01-security §7.7, Decision A, D4, M3, AR): every rule, the
 * change taking effect on the next request, and the Backoffice sessions ending with the last role.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class RoleAssignmentTest {

	static final String USERS = "/api/backoffice/v1/users/";

	@Autowired
	MockMvc mvc;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	IdentityFixtures fixtures;

	@MockitoSpyBean
	RoleQueries roleQueries;

	BackofficeAuth auth;

	UUID ownerId;

	Session owner;

	Session admin;

	UUID adminId;

	String branch;

	@BeforeEach
	void setUp() throws Exception {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.ownerId = this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail());
		grantOwner(this.ownerId);
		this.owner = this.fixtures.openSteppedUpSession(this.ownerId);
		this.adminId = this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail());
		this.admin = this.fixtures.openSteppedUpSession(this.adminId);
		this.branch = branch();
	}

	@Test
	void theRoleCatalogListsEverySystemRoleWithItsPermissions() throws Exception {
		MockHttpServletResponse roles = this.auth.perform(get("/api/backoffice/v1/roles"), this.owner, false);

		assertThat(roles.getStatus()).as(roles.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<List<String>>read(roles.getContentAsString(), "$.items[*].code"))
			.contains("OWNER", "ADMIN", "MANAGER", "STAFF", "BARISTA");
		assertThat(JsonPath.<List<List<String>>>read(roles.getContentAsString(),
				"$.items[?(@.code == 'MANAGER')].permissions").getFirst()).contains("STAFF_VIEW");
	}

	@Test
	void anOwnerGrantsAStoreRoleThatWorksOnTheNextRequestAndIsAudited() throws Exception {
		UUID target = this.fixtures.storeAdminWithoutMfa(IdentityFixtures.uniqueEmail());
		revokeStoreRoles(target);
		Session targetSession = this.fixtures.openSession(target);
		assertThat(this.auth.perform(get("/api/backoffice/v1/roles"), targetSession, false).getStatus()).isEqualTo(403);

		MockHttpServletResponse granted = putStoreRoles(this.owner, target, "ADMIN");

		assertThat(granted.getStatus()).as(granted.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<List<String>>read(granted.getContentAsString(), "$.storeRoles")).containsExactly("ADMIN");
		assertThat(this.auth.perform(get("/api/backoffice/v1/roles"), targetSession, false).getStatus())
			.as("in effect on the next request (M3)").isEqualTo(200);
		assertThat(audits("ROLE_GRANT", target)).isEqualTo(1);
		assertThat(putStoreRoles(this.owner, target, "ADMIN").getStatus()).as("idempotent").isEqualTo(200);
		assertThat(audits("ROLE_GRANT", target)).isEqualTo(1);
	}

	@Test
	void aRoleThatRequiresMfaMakesTheNextLoginEnroll() throws Exception {
		String email = IdentityFixtures.uniqueEmail();
		UUID target = this.fixtures.withoutBackofficeAccess(email);

		assertThat(putStoreRoles(this.owner, target, "ADMIN").getStatus()).isEqualTo(200);

		MockHttpServletResponse login = this.auth.login(email, IdentityFixtures.PASSWORD);
		assertThat(JsonPath.<Boolean>read(login.getContentAsString(), "$.mfaEnrollmentRequired")).isTrue();
	}

	@Test
	void theEscalationRulesAreEnforcedAndLogged() throws Exception {
		UUID target = this.fixtures.withoutBackofficeAccess(IdentityFixtures.uniqueEmail());

		assertProblem(putStoreRoles(this.admin, target, "ADMIN"), 403, "ROLE_ASSIGN_FORBIDDEN_ROLE");
		assertProblem(putStoreRoles(this.admin, this.ownerId), 403, "ROLE_ASSIGN_FORBIDDEN_ROLE");
		assertProblem(putStoreRoles(this.admin, this.adminId), 403, "ROLE_SELF_ASSIGN_FORBIDDEN");
		assertProblem(putBranchRoles(this.owner, this.ownerId, this.branch, "MANAGER"), 403,
				"ROLE_SELF_ASSIGN_FORBIDDEN");
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM iam_security_event WHERE event_type = "
				+ "'PERMISSION_ESCALATION_ATTEMPT' AND user_id = ?").param(UuidBinary.toBytes(this.adminId))
			.query(Integer.class).single()).isEqualTo(3);
	}

	@Test
	void scopesAssignmentsAndUnknownsAreRefused() throws Exception {
		UUID staff = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(staff, UUID.fromString(this.branch), "STAFF");
		String elsewhere = branch();

		assertProblem(putStoreRoles(this.owner, staff, "MANAGER"), 422, "ROLE_SCOPE_MISMATCH");
		assertProblem(putBranchRoles(this.owner, staff, this.branch, "OWNER"), 422, "ROLE_SCOPE_MISMATCH");
		assertProblem(putBranchRoles(this.owner, staff, elsewhere, "BARISTA"), 422, "ROLE_REQUIRES_ASSIGNMENT");
		assertProblem(putBranchRoles(this.owner, staff, this.branch, "NO_SUCH_ROLE"), 400, "VALIDATION_FAILED");
		assertProblem(putStoreRoles(this.owner, UUID.randomUUID(), "ADMIN"), 404, "USER_NOT_FOUND");
		assertProblem(putBranchRoles(this.owner, staff, UUID.randomUUID().toString(), "STAFF"), 404,
				"BRANCH_NOT_FOUND");
		MockHttpServletResponse barista = putBranchRoles(this.owner, staff, this.branch, "BARISTA", "STAFF");
		assertThat(barista.getStatus()).as(barista.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<List<String>>read(barista.getContentAsString(),
				"$.branchRoles[?(@.branchId == '" + this.branch + "')].roleCodes[*]")).containsExactly("BARISTA",
						"STAFF");
	}

	@Test
	void theLastOwnerCannotBeRevoked() throws Exception {
		UUID otherOwner = this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail());
		grantOwner(otherOwner);
		doReturn(1).when(this.roleQueries).countHolders(any());

		assertProblem(putStoreRoles(this.owner, otherOwner, "ADMIN"), 409, "ROLE_LAST_OWNER");
		assertThat(this.roleQueries.storeRoleCodes(otherOwner)).contains("OWNER");
	}

	@Test
	void theLastRoleEndsTheBackofficeSessionsButNotTheCustomerOnes() throws Exception {
		UUID staff = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(staff, UUID.fromString(this.branch), "STAFF");
		Session backoffice = this.fixtures.openSession(staff);
		UUID customerSession = customerSession(staff);

		MockHttpServletResponse revoked = putBranchRoles(this.owner, staff, this.branch);

		assertThat(revoked.getStatus()).as(revoked.getContentAsString()).isEqualTo(200);
		assertThat(this.auth.perform(get("/api/backoffice/v1/auth/me"), backoffice, false).getStatus()).isEqualTo(401);
		assertThat(this.jdbc.sql("SELECT revoked_at IS NULL FROM iam_user_session WHERE id = ?")
			.param(UuidBinary.toBytes(customerSession)).query(Boolean.class).single()).as("Customer session lives")
			.isTrue();
		assertThat(audits("ROLE_REVOKE", staff)).isEqualTo(1);
	}

	@Test
	void changingRolesNeedsAFreshStepUp() throws Exception {
		UUID target = this.fixtures.withoutBackofficeAccess(IdentityFixtures.uniqueEmail());
		Session stale = this.fixtures.openSession(this.ownerId);

		assertProblem(putStoreRoles(stale, target, "ADMIN"), 403, "STEP_UP_REQUIRED");
	}

	// --- review of PR #106 ------------------------------------------------------------------------------------

	@Test
	void theSamePutSentTwiceAtOnceGrantsOnce() throws Exception {
		UUID target = this.fixtures.withoutBackofficeAccess(IdentityFixtures.uniqueEmail());

		var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> statusOf(target));
		var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> statusOf(target));

		assertThat(List.of(first.get(), second.get())).containsExactly(200, 200);
		assertThat(audits("ROLE_GRANT", target)).isEqualTo(1);
	}

	@Test
	void unknownUsersAndBranchesAreNotFoundOnThePuts() throws Exception {
		UUID staff = this.fixtures.branchStaff();

		assertProblem(putStoreRoles(this.owner, UUID.randomUUID(), "ADMIN"), 404, "USER_NOT_FOUND");
		assertProblem(putBranchRoles(this.owner, UUID.randomUUID(), this.branch, "STAFF"), 404, "USER_NOT_FOUND");
		assertProblem(putBranchRoles(this.owner, staff, UUID.randomUUID().toString(), "STAFF"), 404,
				"BRANCH_NOT_FOUND");
	}

	private int statusOf(UUID target) {
		try {
			return putStoreRoles(this.owner, target, "ADMIN").getStatus();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private MockHttpServletResponse putStoreRoles(Session session, UUID userId, String... roles) throws Exception {
		return this.auth.perform(put(USERS + userId + "/store-roles").contentType(MediaType.APPLICATION_JSON)
			.content(codes(roles)), session, true);
	}

	private MockHttpServletResponse putBranchRoles(Session session, UUID userId, String branchId, String... roles)
			throws Exception {
		return this.auth.perform(put(USERS + userId + "/branch-roles/" + branchId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(codes(roles)), session, true);
	}

	private static String codes(String... roles) {
		return "{\"roleCodes\": [" + String.join(", ", java.util.Arrays.stream(roles).map(r -> "\"" + r + "\"")
			.toList()) + "]}";
	}

	private void grantOwner(UUID userId) {
		this.jdbc.sql("""
				INSERT INTO iam_user_store_role (id, user_id, role_id, granted_at)
				SELECT ?, ?, id, ? FROM iam_role WHERE code = 'OWNER'
				""").params(UuidBinary.toBytes(UuidV7.generate()), UuidBinary.toBytes(userId), Instant.now()).update();
	}

	private void revokeStoreRoles(UUID userId) {
		this.jdbc.sql("DELETE FROM iam_user_store_role WHERE user_id = ?").param(UuidBinary.toBytes(userId)).update();
	}

	private UUID customerSession(UUID userId) {
		UUID sessionId = UuidV7.generate();
		Instant now = Instant.now();
		this.jdbc.sql("""
				INSERT INTO iam_user_session (id, user_id, persona, channel, created_at, last_active_at, expires_at)
				VALUES (?, ?, 'CUSTOMER', 'CUSTOMER_WEB', ?, ?, ?)
				""").params(UuidBinary.toBytes(sessionId), UuidBinary.toBytes(userId), now, now, now.plusSeconds(3600))
			.update();
		return sessionId;
	}

	private String branch() throws Exception {
		String code = "R-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/branches")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"branchCode": "%s", "name": "HaYi role", "address": {"line": "1 Lê Lợi", "province": "TP.HCM"}}
					""".formatted(code)), this.owner, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.branchId");
	}

	private int audits(String action, UUID userId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM aud_audit_log WHERE action = ? AND entity_id = ?")
			.params(action, UuidBinary.toBytes(userId)).query(Integer.class).single();
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
