package vn.hayi.shop.branch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
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
import vn.hayi.shop.branch.api.BranchCommitment;
import vn.hayi.shop.branch.api.BranchCommitmentContributor;
import vn.hayi.shop.branch.api.BranchCommitmentType;
import vn.hayi.shop.identity.BackofficeAuth;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.IdentityFixtures;
import vn.hayi.shop.platform.persistence.UuidBinary;

/**
 * Owner/Admin activate, deactivate and close Branches; open commitments reported by other modules block deactivate
 * and close (Branch §4.3, §4.5, API 09 §1.3, Decision P).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class,
		BackofficeBranchLifecycleTest.FakeCommitmentsConfiguration.class })
class BackofficeBranchLifecycleTest {

	static final String BRANCHES = "/api/backoffice/v1/branches";

	@Autowired
	MockMvc mvc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcClient jdbc;

	/** Two contributors, as Order and Group Order will be; field names pick the bean. */
	@Autowired
	FakeCommitments fakeCommitments;

	@Autowired
	FakeCommitments otherModuleCommitments;

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

	@AfterEach
	void reset() {
		this.fakeCommitments.clear();
		this.otherModuleCommitments.clear();
		this.clock.reset();
	}

	@Test
	void draftBranchActivates() throws Exception {
		MockHttpServletResponse activated = command("activate", this.admin);

		assertThat(activated.getStatus()).as(activated.getContentAsString()).isEqualTo(200);
		assertThat(activated.getHeader("ETag")).isEqualTo("\"v1\"");
		assertThat(JsonPath.<String>read(activated.getContentAsString(), "$.status")).isEqualTo("ACTIVE");
		assertThat(JsonPath.<String>read(activated.getContentAsString(), "$.activatedAt")).isNotNull();
		assertAudited("BRANCH_ACTIVATE", "DRAFT", "ACTIVE");
	}

	@Test
	void activeBranchDeactivatesAndReactivates() throws Exception {
		command("activate", this.admin);
		String firstActivation = JsonPath.read(detail().getContentAsString(), "$.activatedAt");
		this.clock.advance(Duration.ofMinutes(5));

		MockHttpServletResponse deactivated = command("deactivate", this.admin);
		assertThat(deactivated.getStatus()).as(deactivated.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<String>read(deactivated.getContentAsString(), "$.status")).isEqualTo("INACTIVE");
		assertAudited("BRANCH_DEACTIVATE", "ACTIVE", "INACTIVE");

		MockHttpServletResponse reactivated = command("activate", this.admin);
		assertThat(JsonPath.<String>read(reactivated.getContentAsString(), "$.status")).isEqualTo("ACTIVE");
		// activatedAt is the latest activation (Decision P2).
		assertThat(JsonPath.<String>read(reactivated.getContentAsString(), "$.activatedAt"))
			.isNotEqualTo(firstActivation);
	}

	@Test
	void invalidTransitionsAreRejected() throws Exception {
		assertProblem(command("deactivate", this.admin), 409, "BRANCH_INVALID_TRANSITION");
		command("activate", this.admin);
		assertProblem(command("activate", this.admin), 409, "BRANCH_INVALID_TRANSITION");
		command("deactivate", this.admin);
		assertProblem(command("deactivate", this.admin), 409, "BRANCH_INVALID_TRANSITION");
	}

	@Test
	void closedBranchIsTerminal() throws Exception {
		Session steppedUp = this.fixtures.openSteppedUpSession(this.adminId);
		assertThat(command("close", steppedUp).getStatus()).isEqualTo(200);

		assertProblem(command("activate", this.admin), 409, "BRANCH_INVALID_TRANSITION");
		assertProblem(command("deactivate", this.admin), 409, "BRANCH_INVALID_TRANSITION");
		assertProblem(command("close", steppedUp), 409, "BRANCH_INVALID_TRANSITION");
	}

	@Test
	void closeWithoutStepUpIsRefused() throws Exception {
		assertProblem(command("close", this.admin), 403, "STEP_UP_REQUIRED");

		assertThat(JsonPath.<String>read(detail().getContentAsString(), "$.status")).isEqualTo("DRAFT");
	}

	@Test
	void closeWithFreshStepUpClosesBranch() throws Exception {
		command("activate", this.admin);

		MockHttpServletResponse closed = command("close", this.fixtures.openSteppedUpSession(this.adminId));

		assertThat(closed.getStatus()).as(closed.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<String>read(closed.getContentAsString(), "$.status")).isEqualTo("CLOSED");
		assertThat(JsonPath.<String>read(closed.getContentAsString(), "$.closedAt")).isNotNull();
		assertAudited("BRANCH_CLOSE", "ACTIVE", "CLOSED");
	}

	@Test
	void closureCheckIsEmptyWithoutOpenCommitments() throws Exception {
		MockHttpServletResponse check = closureCheck();

		assertThat(check.getStatus()).as(check.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<List<Object>>read(check.getContentAsString(), "$.conflicts")).isEmpty();
	}

	@Test
	void closureCheckListsOpenCommitments() throws Exception {
		report(new BranchCommitment(BranchCommitmentType.ACTIVE_ORDERS, 4),
				new BranchCommitment(BranchCommitmentType.FUTURE_SCHEDULED_ORDERS, 9));

		String body = closureCheck().getContentAsString();

		assertThat(JsonPath.<List<String>>read(body, "$.conflicts[*].type")).containsExactly("ACTIVE_ORDERS",
				"FUTURE_SCHEDULED_ORDERS");
		assertThat(JsonPath.<List<Integer>>read(body, "$.conflicts[*].count")).containsExactly(4, 9);
	}

	@Test
	void openCommitmentsBlockDeactivateAndClose() throws Exception {
		command("activate", this.admin);
		report(new BranchCommitment(BranchCommitmentType.OPEN_GROUP_SESSIONS, 1));

		MockHttpServletResponse deactivate = command("deactivate", this.admin);
		assertProblem(deactivate, 409, "BRANCH_HAS_OPEN_COMMITMENTS");
		assertThat(JsonPath.<List<String>>read(deactivate.getContentAsString(), "$.conflicts[*].type"))
			.containsExactly("OPEN_GROUP_SESSIONS");
		assertThat(JsonPath.<List<Integer>>read(deactivate.getContentAsString(), "$.conflicts[*].count"))
			.containsExactly(1);
		assertProblem(command("close", this.fixtures.openSteppedUpSession(this.adminId)), 409,
				"BRANCH_HAS_OPEN_COMMITMENTS");

		assertThat(JsonPath.<String>read(detail().getContentAsString(), "$.status")).isEqualTo("ACTIVE");
		assertThat(audits("BRANCH_DEACTIVATE")).isEmpty();
		assertThat(audits("BRANCH_CLOSE")).isEmpty();
	}

	@Test
	void closingADraftBranchIsAlsoBlockedByOpenCommitments() throws Exception {
		report(new BranchCommitment(BranchCommitmentType.ACTIVE_ORDERS, 1));

		MockHttpServletResponse close = command("close", this.fixtures.openSteppedUpSession(this.adminId));

		assertProblem(close, 409, "BRANCH_HAS_OPEN_COMMITMENTS");
		assertThat(JsonPath.<List<String>>read(close.getContentAsString(), "$.conflicts[*].type"))
			.containsExactly("ACTIVE_ORDERS");
		assertThat(JsonPath.<String>read(detail().getContentAsString(), "$.status")).isEqualTo("DRAFT");
		assertThat(audits("BRANCH_CLOSE")).isEmpty();
	}

	@Test
	void countsOfOneTypeAreSummedAcrossContributors() throws Exception {
		UUID branch = UUID.fromString(this.branchId);
		this.fakeCommitments.report(branch, List.of(new BranchCommitment(BranchCommitmentType.ACTIVE_ORDERS, 2),
				new BranchCommitment(BranchCommitmentType.OPEN_GROUP_SESSIONS, 0)));
		this.otherModuleCommitments.report(branch,
				List.of(new BranchCommitment(BranchCommitmentType.FUTURE_SCHEDULED_ORDERS, 5),
						new BranchCommitment(BranchCommitmentType.ACTIVE_ORDERS, 3)));

		String body = closureCheck().getContentAsString();

		assertThat(JsonPath.<List<String>>read(body, "$.conflicts[*].type")).containsExactly("ACTIVE_ORDERS",
				"FUTURE_SCHEDULED_ORDERS");
		assertThat(JsonPath.<List<Integer>>read(body, "$.conflicts[*].count")).containsExactly(5, 5);
	}

	@Test
	void zeroCountsAreNotConflicts() throws Exception {
		command("activate", this.admin);
		report(new BranchCommitment(BranchCommitmentType.ACTIVE_ORDERS, 0));

		assertThat(JsonPath.<List<Object>>read(closureCheck().getContentAsString(), "$.conflicts")).isEmpty();
		assertThat(command("deactivate", this.admin).getStatus()).isEqualTo(200);
	}

	@Test
	void staleIfMatchOnCommandConflicts() throws Exception {
		MockHttpServletResponse stale = this.auth.perform(post(path("activate")).header("If-Match", "\"v9\""),
				this.admin, true);
		assertProblem(stale, 412, "VERSION_CONFLICT");

		MockHttpServletResponse current = this.auth.perform(post(path("activate")).header("If-Match", "\"v0\""),
				this.admin, true);
		assertThat(current.getStatus()).as(current.getContentAsString()).isEqualTo(200);
	}

	@Test
	void managerCannotChangeLifecycle() throws Exception {
		UUID managerId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(managerId, UUID.fromString(this.branchId), "MANAGER");
		Session manager = this.fixtures.openSession(managerId);

		assertProblem(command("activate", manager), 403, "PERMISSION_DENIED");
		assertProblem(this.auth.perform(get(path("closure-check")), manager, false), 403, "PERMISSION_DENIED");
	}

	@Test
	void unknownBranchIsNotFound() throws Exception {
		assertProblem(this.auth.perform(post(BRANCHES + "/" + UUID.randomUUID() + "/activate"), this.admin, true),
				404, "BRANCH_NOT_FOUND");
	}

	private void report(BranchCommitment... reported) {
		this.fakeCommitments.report(UUID.fromString(this.branchId), List.of(reported));
	}

	private MockHttpServletResponse command(String action, Session session) throws Exception {
		return this.auth.perform(post(path(action)), session, true);
	}

	private MockHttpServletResponse closureCheck() throws Exception {
		return this.auth.perform(get(path("closure-check")), this.admin, false);
	}

	private MockHttpServletResponse detail() throws Exception {
		return this.auth.perform(get(BRANCHES + "/" + this.branchId), this.admin, false);
	}

	private String path(String action) {
		return BRANCHES + "/" + this.branchId + "/" + action;
	}

	private void assertAudited(String action, String oldStatus, String newStatus) {
		List<Map<String, Object>> rows = audits(action);
		assertThat(rows).hasSize(1);
		Map<String, Object> row = rows.getLast();
		assertThat(row).containsEntry("actor_user_id", UuidBinary.toBytes(this.adminId));
		assertThat(JsonPath.<String>read((String) row.get("old_value"), "$.status")).isEqualTo(oldStatus);
		assertThat(JsonPath.<String>read((String) row.get("new_value"), "$.status")).isEqualTo(newStatus);
	}

	private List<Map<String, Object>> audits(String action) {
		return this.jdbc
			.sql("SELECT actor_user_id, CAST(old_value AS CHAR) AS old_value, CAST(new_value AS CHAR) AS new_value"
					+ " FROM aud_audit_log WHERE entity_id = ? AND action = ?")
			.param(UuidBinary.toBytes(UUID.fromString(this.branchId)))
			.param(action)
			.query()
			.listOfRows();
	}

	private String createBranch() throws Exception {
		String code = "T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		MockHttpServletRequestBuilder create = post(BRANCHES).contentType(MediaType.APPLICATION_JSON).content("""
				{"branchCode": "%s", "name": "HaYi test", "address": {"line": "1 Lê Lợi", "province": "TP.HCM"}}
				""".formatted(code));
		MockHttpServletResponse created = this.auth.perform(create, this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.branchId");
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

	/** Stands in for Order / Group Order, which do not exist yet in S1. */
	static class FakeCommitments implements BranchCommitmentContributor {

		private final Map<UUID, List<BranchCommitment>> byBranch = new ConcurrentHashMap<>();

		void report(UUID branchId, List<BranchCommitment> commitments) {
			this.byBranch.put(branchId, commitments);
		}

		void clear() {
			this.byBranch.clear();
		}

		@Override
		public List<BranchCommitment> openCommitments(UUID branchId) {
			return this.byBranch.getOrDefault(branchId, List.of());
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeCommitmentsConfiguration {

		@Bean
		FakeCommitments fakeCommitments() {
			return new FakeCommitments();
		}

		@Bean
		FakeCommitments otherModuleCommitments() {
			return new FakeCommitments();
		}

	}

}
