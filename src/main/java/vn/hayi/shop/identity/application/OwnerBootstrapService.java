package vn.hayi.shop.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.audit.api.AuditEntry;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.audit.api.AuditSource;
import vn.hayi.shop.identity.domain.IdentifierType;
import vn.hayi.shop.identity.domain.LoginIdentifier;
import vn.hayi.shop.identity.domain.LoginIdentifierRepository;
import vn.hayi.shop.identity.domain.PasswordPolicy;
import vn.hayi.shop.identity.domain.User;
import vn.hayi.shop.identity.domain.UserCredential;
import vn.hayi.shop.identity.domain.UserCredentialRepository;
import vn.hayi.shop.identity.domain.UserRepository;
import vn.hayi.shop.identity.domain.UserStoreRole;
import vn.hayi.shop.identity.domain.UserStoreRoleRepository;
import vn.hayi.shop.identity.infrastructure.RoleQueries;
import vn.hayi.shop.shared.actor.ActorContext;

/**
 * Creates the first Owner of a new environment (Identity §2.11 "Owner bootstrap", Decision K1): one transaction
 * creates the user, its email, a temporary credential and the OWNER grant, audited as SYSTEM. If any user already
 * holds OWNER nothing happens, so running it again (or on several instances at once) never adds a second Owner.
 */
@Service
public class OwnerBootstrapService {

	static final String OWNER = "OWNER";

	private final RoleQueries roles;

	private final UserRepository users;

	private final LoginIdentifierRepository identifiers;

	private final UserCredentialRepository credentials;

	private final UserStoreRoleRepository storeRoles;

	private final PasswordEncoder passwordEncoder;

	private final AuditRecorder audit;

	private final Clock clock;

	OwnerBootstrapService(RoleQueries roles, UserRepository users, LoginIdentifierRepository identifiers,
			UserCredentialRepository credentials, UserStoreRoleRepository storeRoles, PasswordEncoder passwordEncoder,
			AuditRecorder audit, Clock clock) {
		this.roles = roles;
		this.users = users;
		this.identifiers = identifiers;
		this.credentials = credentials;
		this.storeRoles = storeRoles;
		this.passwordEncoder = passwordEncoder;
		this.audit = audit;
		this.clock = clock;
	}

	public enum Outcome {

		CREATED,
		/** An Owner already exists. */
		SKIPPED

	}

	@Transactional
	public Outcome bootstrap(String email, String temporaryPassword) {
		UUID ownerRoleId = this.roles.lockRole(OWNER);
		if (this.roles.anyUserHoldsStoreRole(ownerRoleId)) {
			return Outcome.SKIPPED;
		}
		if (this.identifiers.findByTypeAndNormalizedValue(IdentifierType.EMAIL, LoginIdentifier.normalizeEmail(email)).isPresent()) {
			throw new IllegalStateException("Owner bootstrap email already belongs to a user");
		}
		PasswordPolicy.check(temporaryPassword);
		Instant now = Instant.now(this.clock);
		User owner = this.users.save(User.create(now));
		this.identifiers.save(LoginIdentifier.verifiedEmail(owner.getId(), email, now));
		this.credentials.save(UserCredential.temporary(owner.getId(), this.passwordEncoder.encode(temporaryPassword), now));
		this.storeRoles.save(UserStoreRole.grant(owner.getId(), ownerRoleId, null, now));
		this.audit.record(AuditEntry.of(ActorContext.system(), AuditSource.SYSTEM, "ROLE_GRANT", "USER", owner.getId())
			.withNewValue(Map.of("role", OWNER, "scope", "STORE"))
			.withReason("Owner bootstrap"));
		return Outcome.CREATED;
	}

}
