package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.hayi.shop.identity.BackofficeLoginTest.assertProblem;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.application.OwnerBootstrapService;
import vn.hayi.shop.identity.application.OwnerBootstrapService.Outcome;

/**
 * First Owner of a new environment (Identity §2.11 "Owner bootstrap", Decision K1): temporary password that must be
 * changed, audited as SYSTEM, and never a second Owner.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class OwnerBootstrapTest {

	static final String TEMPORARY_PASSWORD = "temporary owner passphrase";

	@Autowired
	OwnerBootstrapService bootstrap;

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	IdentityFixtures fixtures;

	BackofficeAuth auth;

	@BeforeEach
	void newEnvironmentWithoutOwner() {
		this.auth = new BackofficeAuth(this.mvc);
		this.jdbc.sql("DELETE FROM iam_user_store_role WHERE role_id = (SELECT id FROM iam_role WHERE code = 'OWNER')").update();
	}

	@Test
	void bootstrappedOwnerChangesTemporaryPasswordThenLogsIn() throws Exception {
		String email = IdentityFixtures.uniqueEmail();

		assertThat(this.bootstrap.bootstrap(email, TEMPORARY_PASSWORD)).isEqualTo(Outcome.CREATED);

		MockHttpServletResponse first = this.auth.login(email, TEMPORARY_PASSWORD);
		assertThat(JsonPath.<Boolean>read(first.getContentAsString(), "$.passwordChangeRequired")).isTrue();
		String changeToken = JsonPath.read(first.getContentAsString(), "$.changeToken");
		assertThat(this.auth.changeFirstPassword(changeToken, "the owner's own passphrase").getStatus()).isEqualTo(204);
		assertThat(this.auth.loginSession(email, "the owner's own passphrase").access()).isNotBlank();
	}

	@Test
	void bootstrapTwiceCreatesSingleOwner() throws Exception {
		String email = IdentityFixtures.uniqueEmail();
		String secondEmail = IdentityFixtures.uniqueEmail();
		this.bootstrap.bootstrap(email, TEMPORARY_PASSWORD);

		assertThat(this.bootstrap.bootstrap(secondEmail, TEMPORARY_PASSWORD)).isEqualTo(Outcome.SKIPPED);

		assertThat(ownerCount()).isEqualTo(1);
		assertProblem(this.auth.login(secondEmail, TEMPORARY_PASSWORD), 401, "AUTH_INVALID_CREDENTIALS");
	}

	@Test
	void bootstrapRefusesEmailOfAnExistingUser() {
		String email = IdentityFixtures.uniqueEmail();
		this.fixtures.storeAdmin(email);

		assertThatThrownBy(() -> this.bootstrap.bootstrap(email, TEMPORARY_PASSWORD))
			.isInstanceOf(IllegalStateException.class);

		assertThat(ownerCount()).isZero();
	}

	@Test
	void bootstrapIsAuditedAsSystemRoleGrant() {
		String email = IdentityFixtures.uniqueEmail();

		this.bootstrap.bootstrap(email, TEMPORARY_PASSWORD);

		Map<String, Object> audit = this.jdbc.sql("""
				SELECT a.actor_type, a.actor_user_id, a.action, a.entity_type, a.source,
				       JSON_UNQUOTE(JSON_EXTRACT(a.new_value, '$.role')) AS role
				FROM aud_audit_log a
				JOIN iam_login_identifier li ON li.user_id = a.entity_id
				WHERE li.normalized_value = ?
				""").param(email).query().singleRow();
		assertThat(audit).containsEntry("actor_type", "SYSTEM")
			.containsEntry("actor_user_id", null)
			.containsEntry("action", "ROLE_GRANT")
			.containsEntry("entity_type", "USER")
			.containsEntry("source", "SYSTEM")
			.containsEntry("role", "OWNER");
	}

	private long ownerCount() {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM iam_user_store_role usr JOIN iam_role r ON r.id = usr.role_id WHERE r.code = 'OWNER'
				""").query(Long.class).single();
	}

}
