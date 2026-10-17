package vn.hayi.shop.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.audit.api.AuditEntry;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.audit.api.AuditSource;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * Business audit is written in the same transaction as the audited mutation (Audit §17.8): it commits and rolls back
 * with the business change, and never runs on its own.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditRecorderTest {

	@Autowired
	AuditRecorder auditRecorder;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	JdbcClient jdbc;

	@Test
	void auditCommitsWithTheBusinessTransaction() {
		UUID userId = UuidV7.generate();

		this.transaction.executeWithoutResult(status -> this.auditRecorder.record(roleGranted(userId)));

		Map<String, Object> row = this.jdbc.sql("""
				SELECT actor_type, action, entity_type, source, JSON_UNQUOTE(JSON_EXTRACT(new_value, '$.role')) AS role
				FROM aud_audit_log WHERE entity_id = UUID_TO_BIN(?)
				""").param(userId.toString()).query().singleRow();
		assertThat(row).containsEntry("actor_type", "SYSTEM")
			.containsEntry("action", "ROLE_GRANT")
			.containsEntry("entity_type", "USER")
			.containsEntry("source", "SYSTEM")
			.containsEntry("role", "OWNER");
	}

	@Test
	void auditRolledBackWithFailedMutation() {
		UUID userId = UuidV7.generate();

		assertThatThrownBy(() -> this.transaction.executeWithoutResult(status -> {
			this.auditRecorder.record(roleGranted(userId));
			this.jdbc.sql("INSERT INTO iam_role (id) VALUES (NULL)").update();
		})).isInstanceOf(DataAccessException.class);

		assertThat(auditCount(userId)).isZero();
	}

	@Test
	void auditOutsideBusinessTransactionIsRefused() {
		UUID userId = UuidV7.generate();

		assertThatThrownBy(() -> this.auditRecorder.record(roleGranted(userId)))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(auditCount(userId)).isZero();
	}

	private long auditCount(UUID entityId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM aud_audit_log WHERE entity_id = UUID_TO_BIN(?)")
			.param(entityId.toString()).query(Long.class).single();
	}

	private static AuditEntry roleGranted(UUID userId) {
		return AuditEntry.of(ActorContext.system(), AuditSource.SYSTEM, "ROLE_GRANT", "USER", userId)
			.withNewValue(Map.of("role", "OWNER", "scope", "STORE"));
	}

}
