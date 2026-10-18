package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.identity.application.OwnerBootstrapService;

/**
 * Audit and the audited mutation share one transaction (Audit §17.8): when the audit cannot be written, no Owner is
 * created either.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OwnerBootstrapAuditFailureTest {

	@MockitoBean
	AuditRecorder auditRecorder;

	@Autowired
	OwnerBootstrapService bootstrap;

	@Autowired
	JdbcClient jdbc;

	@Test
	void ownerNotCreatedWhenAuditFails() {
		String email = IdentityFixtures.uniqueEmail();
		this.jdbc.sql("DELETE FROM iam_user_store_role WHERE role_id = (SELECT id FROM iam_role WHERE code = 'OWNER')").update();
		willThrow(new IllegalStateException("audit store down")).given(this.auditRecorder).record(any());

		assertThatThrownBy(() -> this.bootstrap.bootstrap(email, OwnerBootstrapTest.TEMPORARY_PASSWORD))
			.hasMessage("audit store down");

		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM iam_login_identifier WHERE normalized_value = ?").param(email)
			.query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM iam_user_store_role usr JOIN iam_role r ON r.id = usr.role_id WHERE r.code = 'OWNER'
				""").query(Long.class).single()).isZero();
	}

}
