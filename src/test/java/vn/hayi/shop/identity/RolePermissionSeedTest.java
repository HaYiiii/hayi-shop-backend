package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.hayi.shop.TestcontainersConfiguration;

/**
 * The Flyway seed is the permission matrix approved in Decision G4 (API 01-security §7), with system roles from
 * Decision D3 and MFA required for STORE roles (Decision D4).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RolePermissionSeedTest {

	/** Copied by hand from API 01-security §7 (✓ = granted). Columns: OWNER ADMIN MANAGER STAFF BARISTA. */
	private static final String MATRIX = """
			CATALOG_VIEW                    ✓ ✓ ✓ ✓ ✓
			CATALOG_MANAGE                  ✓ ✓ — — —
			CATALOG_PRICE_MANAGE            ✓ ✓ — — —
			BRANCH_LISTING_MANAGE           ✓ ✓ ✓ — —
			SOLD_OUT_MANAGE                 ✓ ✓ ✓ ✓ ✓
			BRANCH_VIEW                     ✓ ✓ ✓ ✓ ✓
			BRANCH_MANAGE                   ✓ ✓ — — —
			FULFILLMENT_CONFIG_MANAGE       ✓ ✓ ✓ — —
			ORDER_VIEW                      ✓ ✓ ✓ ✓ ✓
			ORDER_CREATE_ON_BEHALF          ✓ ✓ ✓ ✓ —
			ORDER_CONFIRM                   ✓ ✓ ✓ ✓ —
			ORDER_PREPARE                   ✓ ✓ ✓ ✓ ✓
			ORDER_READY_OVERRIDE            ✓ ✓ ✓ — —
			ORDER_COMPLETE                  ✓ ✓ ✓ ✓ —
			ORDER_CANCEL                    ✓ ✓ ✓ ✓ —
			ORDER_CANCEL_PRIVILEGED         ✓ ✓ ✓ — —
			ORDER_AMEND                     ✓ ✓ ✓ ✓ —
			GROUP_ORDER_VIEW                ✓ ✓ ✓ ✓ —
			PAYMENT_VIEW                    ✓ ✓ ✓ ✓ —
			PAYMENT_VERIFY                  ✓ ✓ ✓ ✓ —
			PAYMENT_HOLD                    ✓ ✓ ✓ — —
			PAYMENT_CONFIG_MANAGE           ✓ ✓ — — —
			REFUND_REQUEST                  ✓ ✓ ✓ ✓ —
			REFUND_APPROVE                  ✓ ✓ ✓ — —
			REFUND_PROCESS                  ✓ ✓ ✓ — —
			PROMOTION_VIEW                  ✓ ✓ ✓ ✓ —
			PROMOTION_MANAGE                ✓ ✓ — — —
			PROMOTION_OVERRIDE              ✓ ✓ — — —
			LOYALTY_VIEW                    ✓ ✓ ✓ ✓ —
			LOYALTY_ADJUST                  ✓ ✓ — — —
			LOYALTY_RECOVERY_WAIVE          ✓ ✓ — — —
			LOYALTY_CONFIG_MANAGE           ✓ ✓ — — —
			CUSTOMER_VIEW                   ✓ ✓ ✓ ✓ —
			CUSTOMER_PII_VIEW               ✓ ✓ ✓ ✓ —
			INVENTORY_VIEW                  ✓ ✓ ✓ ✓ ✓
			INVENTORY_RECEIVE               ✓ ✓ ✓ ✓ —
			INVENTORY_WASTE                 ✓ ✓ ✓ ✓ ✓
			INVENTORY_ADJUST                ✓ ✓ ✓ — —
			INVENTORY_CANCELLATION_RESOLVE  ✓ ✓ ✓ — —
			STOCKTAKE_PERFORM               ✓ ✓ ✓ ✓ —
			STOCKTAKE_APPROVE               ✓ ✓ ✓ — —
			INVENTORY_MASTER_MANAGE         ✓ ✓ — — —
			INVENTORY_SETTING_MANAGE        ✓ ✓ — — —
			STAFF_VIEW                      ✓ ✓ ✓ — —
			STAFF_MANAGE                    ✓ ✓ — — —
			ROLE_ASSIGN                     ✓ ✓ — — —
			REPORT_VIEW_OPERATIONAL         ✓ ✓ ✓ ✓ —
			REPORT_VIEW_FINANCIAL           ✓ ✓ ✓ — —
			REPORT_EXPORT                   ✓ ✓ ✓ — —
			DASHBOARD_MANAGE                ✓ ✓ — — —
			NOTIFICATION_TEMPLATE_MANAGE    ✓ ✓ — — —
			AUDIT_VIEW                      ✓ ✓ — — —
			SECURITY_EVENT_VIEW             ✓ ✓ — — —
			JOB_OPERATE                     ✓ ✓ — — —
			""";

	private static final List<String> ROLES = List.of("OWNER", "ADMIN", "MANAGER", "STAFF", "BARISTA");

	@Autowired
	JdbcClient jdbc;

	@Test
	void seededRolePermissionsMatchApprovedMatrix() {
		Map<String, Set<String>> seeded = this.jdbc.sql("""
				SELECT r.code AS role_code, p.code AS permission_code
				FROM iam_role_permission rp
				JOIN iam_role r ON r.id = rp.role_id
				JOIN iam_permission p ON p.id = rp.permission_id
				""")
			.query((rs, n) -> Map.entry(rs.getString("role_code"), rs.getString("permission_code")))
			.list().stream()
			.collect(Collectors.groupingBy(Map.Entry::getKey, TreeMap::new,
					Collectors.mapping(Map.Entry::getValue, Collectors.toCollection(TreeSet::new))));

		assertThat(seeded).isEqualTo(expectedMatrix());
	}

	@Test
	void permissionCatalogHasExactlyTheMatrixPermissions() {
		Set<String> catalog = new TreeSet<>(this.jdbc.sql("SELECT code FROM iam_permission").query(String.class).list());

		assertThat(catalog).hasSize(54).isEqualTo(MATRIX.lines().map(line -> line.split("\\s+")[0])
			.collect(Collectors.toCollection(TreeSet::new)));
	}

	@Test
	void systemRolesHaveFixedScopesAndStoreRolesRequireMfa() {
		List<String> roles = this.jdbc.sql("SELECT CONCAT(code, ':', allowed_scope, ':', is_system, ':', requires_mfa) FROM iam_role ORDER BY code")
			.query(String.class).list();

		assertThat(roles).containsExactly(
				"ADMIN:STORE:1:1",
				"BARISTA:BRANCH:1:0",
				"MANAGER:BRANCH:1:0",
				"OWNER:STORE:1:1",
				"STAFF:BRANCH:1:0");
	}

	private static Map<String, Set<String>> expectedMatrix() {
		Map<String, Set<String>> expected = new TreeMap<>();
		MATRIX.lines().forEach(line -> {
			String[] cells = line.trim().split("\\s+");
			for (int i = 0; i < ROLES.size(); i++) {
				if (cells[i + 1].equals("✓")) {
					expected.computeIfAbsent(ROLES.get(i), role -> new TreeSet<>()).add(cells[0]);
				}
			}
		});
		assertThat(Arrays.stream(MATRIX.split("\n")).filter(line -> !line.isBlank())).hasSize(54);
		return expected;
	}

}
