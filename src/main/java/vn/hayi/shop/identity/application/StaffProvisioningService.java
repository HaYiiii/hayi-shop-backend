package vn.hayi.shop.identity.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.audit.api.AuditEntry;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.audit.api.AuditSource;
import vn.hayi.shop.branch.api.BranchDirectory;
import vn.hayi.shop.identity.domain.IdentifierType;
import vn.hayi.shop.identity.domain.IdentityErrorCode;
import vn.hayi.shop.identity.domain.LoginIdentifier;
import vn.hayi.shop.identity.domain.LoginIdentifierRepository;
import vn.hayi.shop.identity.domain.PasswordPolicy;
import vn.hayi.shop.identity.domain.User;
import vn.hayi.shop.identity.domain.UserCredential;
import vn.hayi.shop.identity.domain.UserCredentialRepository;
import vn.hayi.shop.identity.domain.UserRepository;
import vn.hayi.shop.identity.infrastructure.RoleQueries;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.staff.api.StaffDirectory;
import vn.hayi.shop.staff.api.StaffErrorCode;

/**
 * Staff provisioning (Identity §2.11, Decision AQ): one transaction resolves or creates the User with its login
 * identifiers and a temporary credential, mirrors the employee code, creates the StaffProfile and assignments
 * through {@code staff.api}, grants the Branch roles and audits it all. It runs in Identity because Identity already
 * reads {@code staff.api} (AQ1).
 */
@Service
public class StaffProvisioningService {

	private static final SecureRandom RANDOM = new SecureRandom();

	/** No look-alike characters, so a temporary password can be read out or typed from a note. */
	private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

	static final int TEMPORARY_PASSWORD_LENGTH = 16;

	private final UserRepository users;

	private final LoginIdentifierRepository identifiers;

	private final UserCredentialRepository credentials;

	private final RoleQueries roles;

	private final StaffDirectory staff;

	private final BranchDirectory branches;

	private final PasswordEncoder passwordEncoder;

	private final AuditRecorder audit;

	private final Clock clock;

	StaffProvisioningService(UserRepository users, LoginIdentifierRepository identifiers,
			UserCredentialRepository credentials, RoleQueries roles, StaffDirectory staff, BranchDirectory branches,
			PasswordEncoder passwordEncoder, AuditRecorder audit, Clock clock) {
		this.users = users;
		this.identifiers = identifiers;
		this.credentials = credentials;
		this.roles = roles;
		this.staff = staff;
		this.branches = branches;
		this.passwordEncoder = passwordEncoder;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * @param loginPhone E.164 or {@code null}; @param loginEmail or {@code null}; at least one is set
	 * @param employeeCode as stored: trimmed, upper-cased
	 * @param credentialMode {@code TEMPORARY_PASSWORD} or {@code INVITATION}
	 */
	public record NewStaff(String employeeCode, String fullName, String loginPhone, String loginEmail,
			String contactPhone, LocalDate joinedOn, List<StaffDirectory.NewAssignment> assignments,
			List<BranchRole> branchRoles, String credentialMode) {
	}

	public record BranchRole(UUID branchId, String roleCode) {
	}

	/** {@code temporaryPassword} is shown once and only for a new User (AQ3, AQ4). */
	public record Provisioned(UUID staffId, UUID userId, boolean linkedExistingUser, String temporaryPassword,
			boolean mfaEnrollmentRequired) {
	}

	@Transactional
	public Provisioned provision(NewStaff request, UUID actorUserId, ClientInfo client) {
		if ("INVITATION".equals(request.credentialMode())) {
			throw new DomainException(IdentityErrorCode.STAFF_INVITATION_UNAVAILABLE,
					"Invitations need a delivery channel (Notification), not available yet");
		}
		Map<BranchRole, RoleQueries.Role> roleByGrant = resolveRoles(request);
		request.assignments().forEach(assignment -> this.branches.requireConfigurable(assignment.branchId()));
		Instant now = this.clock.instant();
		Optional<UUID> existing = existingUser(request);
		String temporaryPassword = null;
		UUID userId;
		if (existing.isPresent()) {
			userId = existing.get();
			if (this.staff.existsForUser(userId)) {
				throw new DomainException(StaffErrorCode.STAFF_ALREADY_EXISTS_FOR_USER,
						"User " + userId + " is already staff");
			}
			addMissingIdentifiers(userId, request, now);
		}
		else {
			userId = this.users.save(User.create(now)).getId();
			addMissingIdentifiers(userId, request, now);
			temporaryPassword = temporaryPassword();
			this.credentials.save(UserCredential.temporary(userId, this.passwordEncoder.encode(temporaryPassword), now));
		}
		UUID staffId = this.staff.create(new StaffDirectory.NewStaff(userId, request.employeeCode(),
				request.fullName(), request.contactPhone(), request.joinedOn(), request.assignments()));
		// Flushes the new User and its identifiers too: the role grants below are plain SQL with a FK to iam_user.
		try {
			this.identifiers.saveAndFlush(LoginIdentifier.employeeCode(userId, request.employeeCode(), now));
		}
		catch (DataIntegrityViolationException ex) {
			// A concurrent create took the same new phone/email after the lookup (review of PR #105, AQ3).
			String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
			if (message != null && message.contains("uk_iam_login_identifier__type_value")) {
				throw new DomainException(IdentityErrorCode.STAFF_IDENTIFIER_CONFLICT,
						"The login phone or email was just taken by another user");
			}
			throw ex;
		}
		ActorContext actor = ActorContext.staff(actorUserId, null);
		Map<String, Object> created = new LinkedHashMap<>();
		created.put("staffId", staffId);
		created.put("userId", userId);
		created.put("employeeCode", request.employeeCode());
		created.put("linkedExistingUser", existing.isPresent());
		created.put("branchIds", request.assignments().stream().map(StaffDirectory.NewAssignment::branchId).toList());
		record(actor, client, "STAFF_CREATE", "STAFF", staffId, created);
		boolean mfaRequired = false;
		for (Map.Entry<BranchRole, RoleQueries.Role> grant : roleByGrant.entrySet()) {
			this.roles.grantBranchRole(userId, grant.getKey().branchId(), grant.getValue().id(), actorUserId, now);
			mfaRequired |= grant.getValue().requiresMfa();
			record(actor, client, "ROLE_GRANT", "USER", userId,
					Map.of("role", grant.getValue().code(), "scope", "BRANCH", "branchId", grant.getKey().branchId()));
		}
		return new Provisioned(staffId, userId, existing.isPresent(), temporaryPassword, mfaRequired);
	}

	/**
	 * Every role is a BRANCH role at a Branch the new staff is assigned to (Decision A, AQ2).
	 * @throws DomainException {@code ROLE_SCOPE_MISMATCH}, {@code ROLE_REQUIRES_ASSIGNMENT}
	 */
	private Map<BranchRole, RoleQueries.Role> resolveRoles(NewStaff request) {
		Set<UUID> assigned = request.assignments()
			.stream()
			.map(StaffDirectory.NewAssignment::branchId)
			.collect(Collectors.toSet());
		Map<BranchRole, RoleQueries.Role> resolved = new LinkedHashMap<>();
		for (BranchRole grant : request.branchRoles()) {
			RoleQueries.Role role = this.roles.findRole(grant.roleCode())
				.orElseThrow(() -> new DomainException(CommonErrorCode.VALIDATION_FAILED,
						"Unknown role " + grant.roleCode()));
			if (!"BRANCH".equals(role.allowedScope())) {
				throw new DomainException(IdentityErrorCode.ROLE_SCOPE_MISMATCH, "Not a BRANCH role: " + grant.roleCode());
			}
			if (!assigned.contains(grant.branchId())) {
				throw new DomainException(IdentityErrorCode.ROLE_REQUIRES_ASSIGNMENT,
						"No assignment at Branch " + grant.branchId());
			}
			resolved.put(grant, role);
		}
		return resolved;
	}

	/**
	 * The User owning the phone or the email (D1).
	 * @throws DomainException {@code STAFF_IDENTIFIER_CONFLICT} when they belong to two Users (AQ3)
	 */
	private Optional<UUID> existingUser(NewStaff request) {
		Optional<UUID> byPhone = Optional.ofNullable(request.loginPhone())
			.flatMap(phone -> this.identifiers.findByTypeAndNormalizedValue(IdentifierType.PHONE, phone))
			.map(LoginIdentifier::getUserId);
		Optional<UUID> byEmail = Optional.ofNullable(request.loginEmail())
			.flatMap(email -> this.identifiers.findByTypeAndNormalizedValue(IdentifierType.EMAIL,
					LoginIdentifier.normalizeEmail(email)))
			.map(LoginIdentifier::getUserId);
		if (byPhone.isPresent() && byEmail.isPresent() && !byPhone.get().equals(byEmail.get())) {
			throw new DomainException(IdentityErrorCode.STAFF_IDENTIFIER_CONFLICT,
					"The phone and the email belong to two different users");
		}
		return byPhone.or(() -> byEmail);
	}

	/** Adds the login phone / email the User does not have yet; the provisioning actor vouches for them. */
	private void addMissingIdentifiers(UUID userId, NewStaff request, Instant now) {
		if (request.loginPhone() != null
				&& this.identifiers.findByUserIdAndType(userId, IdentifierType.PHONE).isEmpty()) {
			this.identifiers.save(LoginIdentifier.verifiedPhone(userId, request.loginPhone(), now));
		}
		if (request.loginEmail() != null
				&& this.identifiers.findByUserIdAndType(userId, IdentifierType.EMAIL).isEmpty()) {
			this.identifiers.save(LoginIdentifier.verifiedEmail(userId, request.loginEmail(), now));
		}
	}

	/** Random, never logged, valid under the internal policy (K4). */
	static String temporaryPassword() {
		while (true) {
			StringBuilder password = new StringBuilder(TEMPORARY_PASSWORD_LENGTH);
			for (int i = 0; i < TEMPORARY_PASSWORD_LENGTH; i++) {
				password.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
			}
			try {
				PasswordPolicy.check(password.toString());
				return password.toString();
			}
			catch (DomainException ex) {
				// A common password by chance: draw again.
			}
		}
	}

	private void record(ActorContext actor, ClientInfo client, String action, String entityType, UUID entityId,
			Map<String, ?> newValue) {
		this.audit.record(AuditEntry.of(actor, AuditSource.BACKOFFICE_WEB, action, entityType, entityId)
			.withNewValue(newValue)
			.withClient(client.ipAddress(), client.userAgent()));
	}

}
