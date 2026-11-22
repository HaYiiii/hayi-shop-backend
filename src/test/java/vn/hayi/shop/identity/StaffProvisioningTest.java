package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.domain.IdentifierType;
import vn.hayi.shop.identity.domain.LoginIdentifierRepository;
import vn.hayi.shop.identity.infrastructure.RoleQueries;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * An Owner/Admin creates staff through the API (Identity §2.11, API 10 §1, Decision AQ): a new User with a temporary
 * password, a phone that is already a Customer, the validation, step-up and permission, the all-or-nothing
 * transaction and the scoped read.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class StaffProvisioningTest {

	static final String STAFF = "/api/backoffice/v1/staff";

	private static final AtomicInteger NEXT = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	PasswordEncoder passwordEncoder;

	@MockitoSpyBean
	RoleQueries roles;

	@MockitoSpyBean
	LoginIdentifierRepository identifiers;

	BackofficeAuth auth;

	Session owner;

	String branch;

	@BeforeEach
	void setUp() throws Exception {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.owner = this.fixtures.openSteppedUpSession(this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail()));
		this.branch = branch();
	}

	@Test
	void aNewStaffMemberGetsATemporaryPasswordAndMustChangeItAtFirstLogin() throws Exception {
		String code = code();
		String email = IdentityFixtures.uniqueEmail();

		MockHttpServletResponse created = create(body(code, null, email, "[" + assignment(this.branch, "PRIMARY") + "]",
				"[" + role(this.branch, "BARISTA") + "]", "TEMPORARY_PASSWORD"));

		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		String body = created.getContentAsString();
		assertThat(JsonPath.<Boolean>read(body, "$.linkedExistingUser")).isFalse();
		assertThat(JsonPath.<Boolean>read(body, "$.mfaEnrollmentRequired")).isFalse();
		String temporary = JsonPath.read(body, "$.temporaryPassword");
		assertThat(temporary).hasSize(16);
		String staffId = JsonPath.read(body, "$.staffId");
		assertThat(created.getHeader("Location")).endsWith("/staff/" + staffId);
		assertThat(this.jdbc.sql("SELECT normalized_value FROM iam_login_identifier WHERE identifier_type = "
				+ "'EMPLOYEE_CODE' AND user_id = UUID_TO_BIN(?)").param(JsonPath.<String>read(body, "$.userId"))
			.query(String.class).single()).as("employee code mirrored, upper-case").isEqualTo(code.toUpperCase());
		assertThat(audits("STAFF_CREATE", staffId)).isEqualTo(1);
		assertThat(audits("ROLE_GRANT", JsonPath.read(body, "$.userId"))).isEqualTo(1);

		MockHttpServletResponse login = this.auth.login(email, temporary);
		assertThat(login.getStatus()).as(login.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<Boolean>read(login.getContentAsString(), "$.passwordChangeRequired")).isTrue();
		assertThat(this.auth.changeFirstPassword(JsonPath.read(login.getContentAsString(), "$.changeToken"),
				"pha che ca phe sua da").getStatus()).isEqualTo(204);
		MockHttpServletResponse byCode = this.auth.login(code.toLowerCase(), "pha che ca phe sua da");
		assertThat(byCode.getStatus()).as("staff log in by employee code").isEqualTo(200);
		assertThat(byCode.getCookie("hy_b_at")).isNotNull();
	}

	@Test
	void aPhoneThatIsAlreadyACustomerLinksThatUserWithoutANewPassword() throws Exception {
		String national = "07" + String.format("%08d", (Instant.now().toEpochMilli() * 10 + NEXT.getAndIncrement())
				% 100_000_000L);
		UUID customer = customerWithPhone("+84" + national.substring(1));

		MockHttpServletResponse created = create(body(code(), national, null,
				"[" + assignment(this.branch, "PRIMARY") + "]", "[]", "TEMPORARY_PASSWORD"));

		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		assertThat(JsonPath.<Boolean>read(created.getContentAsString(), "$.linkedExistingUser")).isTrue();
		assertThat(JsonPath.<Object>read(created.getContentAsString(), "$.temporaryPassword")).isNull();
		assertThat(JsonPath.<String>read(created.getContentAsString(), "$.userId")).isEqualTo(customer.toString());
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM iam_user_credential WHERE user_id = ?")
			.param(UuidBinary.toBytes(customer)).query(Integer.class).single()).isEqualTo(1);
		assertProblem(create(body(code(), national, null, "[" + assignment(this.branch, "PRIMARY") + "]", "[]",
				"TEMPORARY_PASSWORD")), 409, "STAFF_ALREADY_EXISTS_FOR_USER");
	}

	@Test
	void theRequestIsValidatedAgainstTheStaffRules() throws Exception {
		String other = branch();
		String code = code();
		String one = "[" + assignment(this.branch, "PRIMARY") + "]";
		assertThat(create(body(code, null, IdentityFixtures.uniqueEmail(), one, "[]", "TEMPORARY_PASSWORD"))
			.getStatus()).isEqualTo(201);

		assertProblem(create(body(code, null, IdentityFixtures.uniqueEmail(), one, "[]", "TEMPORARY_PASSWORD")), 409,
				"STAFF_EMPLOYEE_CODE_TAKEN");
		assertProblem(create(body(code(), null, IdentityFixtures.uniqueEmail(), one, "[" + role(other, "STAFF") + "]",
				"TEMPORARY_PASSWORD")), 422, "ROLE_REQUIRES_ASSIGNMENT");
		assertProblem(create(body(code(), null, IdentityFixtures.uniqueEmail(), one,
				"[" + role(this.branch, "OWNER") + "]", "TEMPORARY_PASSWORD")), 422, "ROLE_SCOPE_MISMATCH");
		assertProblem(create(body(code(), null, IdentityFixtures.uniqueEmail(),
				"[" + assignment(this.branch, "PRIMARY") + ", " + assignment(other, "PRIMARY") + "]", "[]",
				"TEMPORARY_PASSWORD")), 422, "ASSIGNMENT_PRIMARY_CONFLICT");
		assertProblem(create(body(code(), null, IdentityFixtures.uniqueEmail(),
				"[" + assignment(this.branch, "PRIMARY") + ", " + assignment(this.branch, "SUPPORTING") + "]", "[]",
				"TEMPORARY_PASSWORD")), 422, "ASSIGNMENT_DUPLICATE_ACTIVE");
		assertProblem(create(body(code(), null, IdentityFixtures.uniqueEmail(), one, "[]", "INVITATION")), 422,
				"STAFF_INVITATION_UNAVAILABLE");
		assertProblem(create(body(code(), null, IdentityFixtures.uniqueEmail(),
				"[" + assignment(UUID.randomUUID().toString(), "PRIMARY") + "]", "[]", "TEMPORARY_PASSWORD")), 404,
				"BRANCH_NOT_FOUND");
		assertProblem(create(body(code(), null, null, one, "[]", "TEMPORARY_PASSWORD")), 400, "VALIDATION_FAILED");
		assertProblem(create(body(code(), "12345", null, one, "[]", "TEMPORARY_PASSWORD")), 400, "VALIDATION_FAILED");
	}

	@Test
	void aPhoneAndAnEmailOfTwoUsersConflict() throws Exception {
		String national = "07" + String.format("%08d", (Instant.now().toEpochMilli() * 10 + NEXT.getAndIncrement())
				% 100_000_000L);
		customerWithPhone("+84" + national.substring(1));
		String adminEmail = IdentityFixtures.uniqueEmail();
		this.fixtures.storeAdmin(adminEmail);

		assertProblem(create(body(code(), national, adminEmail, "[" + assignment(this.branch, "PRIMARY") + "]", "[]",
				"TEMPORARY_PASSWORD")), 409, "STAFF_IDENTIFIER_CONFLICT");
	}

	@Test
	void creatingStaffNeedsTheStaffManagePermissionAndAFreshStepUp() throws Exception {
		String one = "[" + assignment(this.branch, "PRIMARY") + "]";
		Session notSteppedUp = this.fixtures.openSession(this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail()));
		UUID managerId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(managerId, UUID.fromString(this.branch), "MANAGER");
		Session manager = this.fixtures.openSteppedUpSession(managerId);

		assertProblem(createAs(notSteppedUp, body(code(), null, IdentityFixtures.uniqueEmail(), one, "[]",
				"TEMPORARY_PASSWORD")), 403, "STEP_UP_REQUIRED");
		assertProblem(createAs(manager, body(code(), null, IdentityFixtures.uniqueEmail(), one, "[]",
				"TEMPORARY_PASSWORD")), 403, "PERMISSION_DENIED");
	}

	@Test
	void aFailureHalfwayLeavesNoUserAndNoStaffBehind() throws Exception {
		String email = IdentityFixtures.uniqueEmail();
		String code = code();
		doThrow(new IllegalStateException("grant store down")).when(this.roles)
			.grantBranchRole(any(), any(), any(), any(), any());

		assertThat(create(body(code, null, email, "[" + assignment(this.branch, "PRIMARY") + "]",
				"[" + role(this.branch, "STAFF") + "]", "TEMPORARY_PASSWORD")).getStatus()).isEqualTo(500);

		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM iam_login_identifier WHERE normalized_value = ?")
			.param(email.toLowerCase()).query(Integer.class).single()).isZero();
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM stf_staff_profile WHERE employee_code = ?")
			.param(code.toUpperCase()).query(Integer.class).single()).isZero();
	}

	@Test
	void theReadShowsStaffOnlyWithinTheCallersBranches() throws Exception {
		MockHttpServletResponse created = create(body(code(), null, IdentityFixtures.uniqueEmail(),
				"[" + assignment(this.branch, "PRIMARY") + "]", "[" + role(this.branch, "BARISTA") + "]",
				"TEMPORARY_PASSWORD"));
		String staffId = JsonPath.read(created.getContentAsString(), "$.staffId");
		UUID managerHere = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(managerHere, UUID.fromString(this.branch), "MANAGER");
		UUID managerElsewhere = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(managerElsewhere, UUID.fromString(branch()), "MANAGER");

		MockHttpServletResponse read = this.auth.perform(get(STAFF + "/" + staffId), this.owner, false);
		assertThat(read.getStatus()).as(read.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<String>read(read.getContentAsString(), "$.status")).isEqualTo("ACTIVE");
		assertThat(JsonPath.<List<String>>read(read.getContentAsString(), "$.assignments[*].assignmentType"))
			.containsExactly("PRIMARY");
		assertThat(JsonPath.<List<String>>read(read.getContentAsString(), "$.branchRoles[*].roleCode"))
			.containsExactly("BARISTA");
		assertThat(this.auth.perform(get(STAFF + "/" + staffId), this.fixtures.openSession(managerHere), false)
			.getStatus()).isEqualTo(200);
		assertProblem(this.auth.perform(get(STAFF + "/" + staffId), this.fixtures.openSession(managerElsewhere), false),
				404, "STAFF_NOT_FOUND");
		assertProblem(this.auth.perform(get(STAFF + "/" + UUID.randomUUID()), this.owner, false), 404,
				"STAFF_NOT_FOUND");
	}

	// --- review of PR #105 ------------------------------------------------------------------------------------

	@Test
	void twoCreatesWithOneNewEmailAtOnceEndInAConflictNotAnError() throws Exception {
		String email = IdentityFixtures.uniqueEmail();
		String one = "[" + assignment(this.branch, "PRIMARY") + "]";
		assertThat(create(body(code(), null, email, one, "[]", "TEMPORARY_PASSWORD")).getStatus()).isEqualTo(201);
		// The second request looked the email up before the first one committed.
		doReturn(Optional.empty()).when(this.identifiers).findByTypeAndNormalizedValue(IdentifierType.EMAIL,
				email.toLowerCase());

		assertProblem(create(body(code(), null, email, one, "[]", "TEMPORARY_PASSWORD")), 409,
				"STAFF_IDENTIFIER_CONFLICT");
	}

	@Test
	void anUnknownRoleIsAMalformedRequest() throws Exception {
		assertProblem(create(body(code(), null, IdentityFixtures.uniqueEmail(),
				"[" + assignment(this.branch, "PRIMARY") + "]", "[" + role(this.branch, "NO_SUCH_ROLE") + "]",
				"TEMPORARY_PASSWORD")), 400, "VALIDATION_FAILED");
	}

	@Test
	void anInactiveStaffMemberWithoutAStoreRoleCannotLogIn() throws Exception {
		String email = IdentityFixtures.uniqueEmail();
		MockHttpServletResponse created = create(body(code(), null, email,
				"[" + assignment(this.branch, "PRIMARY") + "]", "[]", "TEMPORARY_PASSWORD"));
		String temporary = JsonPath.read(created.getContentAsString(), "$.temporaryPassword");
		this.jdbc.sql("UPDATE stf_staff_profile SET status = 'INACTIVE' WHERE id = UUID_TO_BIN(?)")
			.param(JsonPath.<String>read(created.getContentAsString(), "$.staffId")).update();

		assertProblem(this.auth.login(email, temporary), 401, "AUTH_INVALID_CREDENTIALS");
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private static String code() {
		return "nv-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private static String assignment(String branchId, String type) {
		return "{\"branchId\": \"%s\", \"assignmentType\": \"%s\", \"effectiveFrom\": \"2026-11-01\"}"
			.formatted(branchId, type);
	}

	private static String role(String branchId, String roleCode) {
		return "{\"branchId\": \"%s\", \"roleCode\": \"%s\"}".formatted(branchId, roleCode);
	}

	private static String body(String code, String phone, String email, String assignments, String roles,
			String mode) {
		return """
				{"employeeCode": "%s", "fullName": "Trần Minh", "loginPhone": %s, "loginEmail": %s,
				 "assignments": %s, "branchRoles": %s, "credential": {"mode": "%s"}}
				""".formatted(code, (phone != null) ? "\"" + phone + "\"" : "null",
				(email != null) ? "\"" + email + "\"" : "null", assignments, roles, mode);
	}

	private MockHttpServletResponse create(String json) throws Exception {
		return createAs(this.owner, json);
	}

	private MockHttpServletResponse createAs(Session session, String json) throws Exception {
		return this.auth.perform(post(STAFF).contentType(MediaType.APPLICATION_JSON).content(json), session, true);
	}

	private String branch() throws Exception {
		String code = "S-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/branches")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"branchCode": "%s", "name": "HaYi NV", "address": {"line": "1 Lê Lợi", "province": "TP.HCM"}}
					""".formatted(code)), this.owner, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.branchId");
	}

	private UUID customerWithPhone(String e164) {
		UUID userId = UuidV7.generate();
		Instant now = Instant.now();
		this.jdbc.sql("INSERT INTO iam_user (id, status, created_at, updated_at) VALUES (?, 'ACTIVE', ?, ?)")
			.params(UuidBinary.toBytes(userId), now, now).update();
		this.jdbc.sql("""
				INSERT INTO iam_login_identifier (id, user_id, identifier_type, normalized_value, verified_at, created_at,
				  updated_at)
				VALUES (?, ?, 'PHONE', ?, ?, ?, ?)
				""").params(UuidBinary.toBytes(UuidV7.generate()), UuidBinary.toBytes(userId), e164, now, now, now)
			.update();
		this.jdbc.sql("""
				INSERT INTO iam_user_credential (user_id, password_hash, must_change_password, password_changed_at, updated_at)
				VALUES (?, ?, 0, ?, ?)
				""").params(UuidBinary.toBytes(userId), this.passwordEncoder.encode("ca phe sua da"), now, now).update();
		return userId;
	}

	private int audits(String action, String entityId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM aud_audit_log WHERE action = ? AND entity_id = UUID_TO_BIN(?)")
			.params(action, entityId).query(Integer.class).single();
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
