package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.platform.security.Permission;

/** The permission catalog in code is the Flyway seed, with action S as marked in API 01-security §7 (Decision M5). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PermissionCatalogTest {

	@Autowired
	JdbcClient jdbc;

	@Test
	void catalogInCodeMatchesSeed() {
		List<String> seeded = this.jdbc.sql("SELECT code FROM iam_permission").query(String.class).list();

		assertThat(Arrays.stream(Permission.values()).map(Permission::name)).containsExactlyInAnyOrderElementsOf(seeded);
	}

	@Test
	void actionsMarkedSInMatrixRequireStepUp() {
		assertThat(Arrays.stream(Permission.values()).filter(Permission::requiresStepUp)).containsExactlyInAnyOrder(
				Permission.PAYMENT_CONFIG_MANAGE, Permission.REFUND_APPROVE, Permission.PROMOTION_OVERRIDE,
				Permission.LOYALTY_ADJUST, Permission.LOYALTY_RECOVERY_WAIVE, Permission.STAFF_MANAGE,
				Permission.ROLE_ASSIGN, Permission.REPORT_EXPORT, Permission.JOB_OPERATE);
	}

}
