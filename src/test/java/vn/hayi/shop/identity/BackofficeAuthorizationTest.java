package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static vn.hayi.shop.identity.BackofficeLoginTest.assertProblem;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.platform.security.Permission;
import vn.hayi.shop.platform.security.RequiresPermission;
import vn.hayi.shop.platform.security.RequiresStepUp;

/**
 * Permission and branch scope on Backoffice endpoints (Identity §2.7, API README §3.1, API 01-security §7, Decision A,
 * M): checked permission → branch scope → step-up before the handler runs, resolved live on every request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class,
		BackofficeAuthorizationTest.BranchActionProbe.class })
class BackofficeAuthorizationTest {

	static final String PROBE = "/api/backoffice/v1/test-probe";

	@Autowired
	MockMvc mvc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	MutableClock clock;

	BackofficeAuth auth;

	UUID branchA;

	UUID branchB;

	@BeforeEach
	void setUp() {
		this.clock.freeze();
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.branchA = UUID.randomUUID();
		this.branchB = UUID.randomUUID();
	}

	@AfterEach
	void resetClock() {
		this.clock.reset();
	}

	@Test
	void baristaReadsOrdersOfAssignedBranch() throws Exception {
		Session barista = staffAt(this.branchA, "BARISTA");

		assertThat(orders(barista, this.branchA).getStatus()).isEqualTo(204);
	}

	@Test
	void baristaWithoutRefundApproveIsDeniedBeforeStepUp() throws Exception {
		Session barista = staffAt(this.branchA, "BARISTA");

		assertProblem(approveRefund(barista, this.branchA), 403, "PERMISSION_DENIED");
	}

	@Test
	void missingPermissionIsReportedBeforeBranchScope() throws Exception {
		Session barista = staffAt(this.branchA, "BARISTA");

		assertProblem(approveRefund(barista, this.branchB), 403, "PERMISSION_DENIED");
		assertProblem(this.auth.perform(post(PROBE + "/branches/not-a-uuid/refunds/approve"), barista, true), 403,
				"PERMISSION_DENIED");
	}

	@Test
	void managerCallingAnotherBranchIsDeniedByScope() throws Exception {
		Session manager = staffAt(this.branchA, "MANAGER");

		assertProblem(orders(manager, this.branchB), 403, "BRANCH_SCOPE_DENIED");
	}

	@Test
	void roleAtOneBranchDoesNotCarryPermissionToAnother() throws Exception {
		UUID userId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(userId, this.branchA, "MANAGER");
		this.fixtures.assignToBranch(userId, this.branchB, "BARISTA");
		Session session = this.fixtures.openSession(userId);

		assertProblem(cancelPrivileged(session, this.branchB), 403, "PERMISSION_DENIED");
		assertThat(cancelPrivileged(session, this.branchA).getStatus()).isEqualTo(204);
	}

	@Test
	void branchRoleWithoutActiveAssignmentGrantsNothing() throws Exception {
		LocalDate today = this.fixtures.today();
		UUID ended = this.fixtures.branchStaff();
		this.fixtures.addAssignment(ended, this.branchA, "ENDED", today.minusDays(9), today.minusDays(2));
		this.fixtures.grantBranchRole(ended, this.branchA, "MANAGER");
		UUID notYet = this.fixtures.branchStaff();
		this.fixtures.addAssignment(notYet, this.branchA, "ACTIVE", today.plusDays(1), null);
		this.fixtures.grantBranchRole(notYet, this.branchA, "MANAGER");
		UUID roleOnly = this.fixtures.branchStaff();
		this.fixtures.grantBranchRole(roleOnly, this.branchA, "MANAGER");

		for (UUID userId : new UUID[] { ended, notYet, roleOnly }) {
			assertProblem(orders(this.fixtures.openSession(userId), this.branchA), 403, "PERMISSION_DENIED");
		}
	}

	@Test
	void assignmentRangeIncludesFirstAndLastDay() throws Exception {
		LocalDate today = this.fixtures.today();
		UUID startsToday = this.fixtures.branchStaff();
		this.fixtures.addAssignment(startsToday, this.branchA, "ACTIVE", today, null);
		this.fixtures.grantBranchRole(startsToday, this.branchA, "BARISTA");
		UUID endsToday = this.fixtures.branchStaff();
		this.fixtures.addAssignment(endsToday, this.branchA, "ACTIVE", today.minusDays(3), today);
		this.fixtures.grantBranchRole(endsToday, this.branchA, "BARISTA");

		assertThat(orders(this.fixtures.openSession(startsToday), this.branchA).getStatus()).isEqualTo(204);
		assertThat(orders(this.fixtures.openSession(endsToday), this.branchA).getStatus()).isEqualTo(204);
	}

	@Test
	void activeAssignmentPastItsLastDayGrantsNothing() throws Exception {
		LocalDate today = this.fixtures.today();
		UUID userId = this.fixtures.branchStaff();
		this.fixtures.addAssignment(userId, this.branchA, "ACTIVE", today.minusDays(5), today.minusDays(1));
		this.fixtures.grantBranchRole(userId, this.branchA, "MANAGER");

		assertProblem(orders(this.fixtures.openSession(userId), this.branchA), 403, "PERMISSION_DENIED");
	}

	@Test
	void storeRoleSeesEveryBranch() throws Exception {
		Session admin = this.fixtures.openSession(this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail()));

		assertThat(orders(admin, this.branchA).getStatus()).isEqualTo(204);
		assertThat(orders(admin, UUID.randomUUID()).getStatus()).isEqualTo(204);
	}

	@Test
	void permittedActionMarkedSStillNeedsStepUp() throws Exception {
		Session admin = this.fixtures.openSession(this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail()));

		assertProblem(approveRefund(admin, this.branchA), 403, "STEP_UP_REQUIRED");
	}

	@Test
	void crossBranchEndpointNeedsPermissionInSomeScope() throws Exception {
		assertThat(crossBranchOrders(staffAt(this.branchA, "BARISTA")).getStatus()).isEqualTo(204);
		assertProblem(crossBranchCatalog(staffAt(this.branchA, "MANAGER")), 403, "PERMISSION_DENIED");
	}

	@Test
	void malformedBranchIdIsOutOfScope() throws Exception {
		Session manager = staffAt(this.branchA, "MANAGER");

		assertProblem(this.auth.perform(get(PROBE + "/branches/not-a-uuid/orders"), manager, false), 403,
				"BRANCH_SCOPE_DENIED");
	}

	@Test
	void revokedBranchRoleIsDeniedOnNextRequest() throws Exception {
		UUID userId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(userId, this.branchA, "MANAGER");
		Session session = this.fixtures.openSession(userId);
		assertThat(orders(session, this.branchA).getStatus()).isEqualTo(204);

		this.fixtures.revokeBranchRole(userId, this.branchA, "MANAGER");

		assertProblem(orders(session, this.branchA), 403, "PERMISSION_DENIED");
	}

	@Test
	void endedAssignmentIsDeniedOnNextRequest() throws Exception {
		UUID userId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(userId, this.branchA, "MANAGER");
		this.fixtures.assignToBranch(userId, this.branchB, "MANAGER");
		Session session = this.fixtures.openSession(userId);
		assertThat(orders(session, this.branchA).getStatus()).isEqualTo(204);

		this.fixtures.endAssignment(userId, this.branchA);

		assertProblem(orders(session, this.branchA), 403, "BRANCH_SCOPE_DENIED");
	}

	@Test
	void inactiveStaffProfileLosesBranchAccessOnNextRequest() throws Exception {
		UUID userId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(userId, this.branchA, "MANAGER");
		Session session = this.fixtures.openSession(userId);
		assertThat(orders(session, this.branchA).getStatus()).isEqualTo(204);

		this.fixtures.deactivateStaff(userId);

		assertProblem(orders(session, this.branchA), 403, "PERMISSION_DENIED");
	}

	@Test
	void revokedStoreRoleIsDeniedOnNextRequest() throws Exception {
		UUID userId = this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail());
		Session session = this.fixtures.openSession(userId);
		assertThat(orders(session, this.branchA).getStatus()).isEqualTo(204);

		this.fixtures.revokeStoreRole(userId, "ADMIN");

		assertProblem(orders(session, this.branchA), 403, "PERMISSION_DENIED");
	}

	@Test
	void grantedRoleTakesEffectOnNextRequest() throws Exception {
		UUID userId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(userId, this.branchA, "BARISTA");
		Session session = this.fixtures.openSession(userId);
		assertProblem(cancelPrivileged(session, this.branchA), 403, "PERMISSION_DENIED");

		this.fixtures.grantBranchRole(userId, this.branchA, "MANAGER");

		assertThat(cancelPrivileged(session, this.branchA).getStatus()).isEqualTo(204);
	}

	private Session staffAt(UUID branchId, String role) {
		UUID userId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(userId, branchId, role);
		return this.fixtures.openSession(userId);
	}

	private MockHttpServletResponse orders(Session session, UUID branchId) throws Exception {
		return this.auth.perform(get(PROBE + "/branches/{branchId}/orders", branchId), session, false);
	}

	private MockHttpServletResponse cancelPrivileged(Session session, UUID branchId) throws Exception {
		return this.auth.perform(post(PROBE + "/branches/{branchId}/orders/cancel-privileged", branchId), session, true);
	}

	private MockHttpServletResponse approveRefund(Session session, UUID branchId) throws Exception {
		return this.auth.perform(post(PROBE + "/branches/{branchId}/refunds/approve", branchId), session, true);
	}

	private MockHttpServletResponse crossBranchOrders(Session session) throws Exception {
		return this.auth.perform(get(PROBE + "/orders"), session, false);
	}

	private MockHttpServletResponse crossBranchCatalog(Session session) throws Exception {
		return this.auth.perform(post(PROBE + "/catalog"), session, true);
	}

	/** Stand-ins for Backoffice endpoints until the business slices add real ones. */
	@TestConfiguration
	@RestController
	static class BranchActionProbe {

		@GetMapping(PROBE + "/branches/{branchId}/orders")
		@RequiresPermission(Permission.ORDER_VIEW)
		ResponseEntity<Void> orders(@PathVariable String branchId) {
			return ResponseEntity.noContent().build();
		}

		@PostMapping(PROBE + "/branches/{branchId}/orders/cancel-privileged")
		@RequiresPermission(Permission.ORDER_CANCEL_PRIVILEGED)
		ResponseEntity<Void> cancelPrivileged(@PathVariable UUID branchId) {
			return ResponseEntity.noContent().build();
		}

		@PostMapping(PROBE + "/branches/{branchId}/refunds/approve")
		@RequiresPermission(Permission.REFUND_APPROVE)
		@RequiresStepUp
		ResponseEntity<Void> approveRefund(@PathVariable UUID branchId) {
			return ResponseEntity.noContent().build();
		}

		@GetMapping(PROBE + "/orders")
		@RequiresPermission(Permission.ORDER_VIEW)
		ResponseEntity<Void> crossBranchOrders() {
			return ResponseEntity.noContent().build();
		}

		@PostMapping(PROBE + "/catalog")
		@RequiresPermission(Permission.CATALOG_MANAGE)
		ResponseEntity<Void> catalog() {
			return ResponseEntity.noContent().build();
		}

	}

}
