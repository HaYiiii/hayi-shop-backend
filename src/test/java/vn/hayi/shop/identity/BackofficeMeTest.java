package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static vn.hayi.shop.identity.BackofficeLoginTest.assertProblem;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.platform.security.Permission;

/**
 * {@code GET /auth/me} lists the caller's roles and permissions per Branch for the UI guard (API 01-security §6.2,
 * Decision M4), resolved live.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeMeTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	MutableClock clock;

	BackofficeAuth auth;

	@BeforeEach
	void setUp() {
		this.clock.freeze();
		this.auth = new BackofficeAuth(this.mvc, this.clock);
	}

	@AfterEach
	void resetClock() {
		this.clock.reset();
	}

	@Test
	void storeAdminGetsStorePermissionsForEveryBranch() throws Exception {
		UUID userId = this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail());

		String body = me(this.fixtures.openSession(userId)).getContentAsString();

		assertThat(JsonPath.<String>read(body, "$.userId")).isEqualTo(userId.toString());
		assertThat(JsonPath.<List<String>>read(body, "$.storeRoles")).containsExactly("ADMIN");
		assertThat(JsonPath.<List<String>>read(body, "$.storePermissions"))
			.contains("ROLE_ASSIGN", "REFUND_APPROVE", "CATALOG_MANAGE").hasSize(Permission.values().length);
		assertThat(JsonPath.<List<Object>>read(body, "$.branches")).isEmpty();
	}

	@Test
	void staffGetsPermissionsPerAssignedBranch() throws Exception {
		UUID branchA = UUID.randomUUID();
		UUID branchB = UUID.randomUUID();
		UUID ended = UUID.randomUUID();
		UUID userId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(userId, branchA, "MANAGER");
		this.fixtures.assignToBranch(userId, branchB, "BARISTA");
		this.fixtures.assignToBranch(userId, ended, "STAFF");
		this.fixtures.endAssignment(userId, ended);

		String body = me(this.fixtures.openSession(userId)).getContentAsString();

		assertThat(JsonPath.<List<String>>read(body, "$.storeRoles")).isEmpty();
		assertThat(JsonPath.<List<String>>read(body, "$.storePermissions")).isEmpty();
		List<Map<String, Object>> branches = JsonPath.read(body, "$.branches");
		assertThat(branches).extracting(branch -> branch.get("branchId"))
			.containsExactlyInAnyOrder(branchA.toString(), branchB.toString());
		Map<String, Object> barista = branches.stream().filter(b -> b.get("branchId").equals(branchB.toString()))
			.findFirst().orElseThrow();
		assertThat(barista.get("roles")).isEqualTo(List.of("BARISTA"));
		assertThat(barista.get("permissions")).asInstanceOf(InstanceOfAssertFactories.LIST)
			.containsExactlyInAnyOrder("CATALOG_VIEW", "SOLD_OUT_MANAGE", "BRANCH_VIEW", "ORDER_VIEW", "ORDER_PREPARE",
					"INVENTORY_VIEW", "INVENTORY_WASTE");
	}

	@Test
	void branchPermissionsIncludeStorePermissions() throws Exception {
		UUID branchA = UUID.randomUUID();
		UUID userId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(userId, branchA, "BARISTA");
		this.fixtures.grantStoreRole(userId, "ADMIN");

		String body = me(this.fixtures.openSession(userId)).getContentAsString();

		assertThat(JsonPath.<List<String>>read(body, "$.branches[0].roles")).containsExactly("BARISTA");
		assertThat(JsonPath.<List<String>>read(body, "$.branches[0].permissions")).hasSize(Permission.values().length);
	}

	@Test
	void roleChangeShowsOnNextCall() throws Exception {
		UUID branchA = UUID.randomUUID();
		UUID userId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(userId, branchA, "BARISTA");
		Session session = this.fixtures.openSession(userId);
		assertThat(JsonPath.<List<String>>read(me(session).getContentAsString(), "$.branches[0].roles"))
			.containsExactly("BARISTA");

		this.fixtures.grantBranchRole(userId, branchA, "MANAGER");
		this.fixtures.revokeBranchRole(userId, branchA, "BARISTA");

		assertThat(JsonPath.<List<String>>read(me(session).getContentAsString(), "$.branches[0].roles"))
			.containsExactly("MANAGER");
	}

	@Test
	void inactiveStaffProfileListsNoBranches() throws Exception {
		UUID userId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(userId, UUID.randomUUID(), "MANAGER");
		this.fixtures.deactivateStaff(userId);

		String body = me(this.fixtures.openSession(userId)).getContentAsString();

		assertThat(JsonPath.<List<Object>>read(body, "$.branches")).isEmpty();
	}

	@Test
	void meNeedsSession() throws Exception {
		assertProblem(this.mvc.perform(get(BackofficeAuth.AUTH + "/me")).andReturn().getResponse(), 401, "AUTH_REQUIRED");
	}

	private MockHttpServletResponse me(Session session) throws Exception {
		MockHttpServletResponse response = this.auth.perform(get(BackofficeAuth.AUTH + "/me"), session, false);
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		return response;
	}

}
