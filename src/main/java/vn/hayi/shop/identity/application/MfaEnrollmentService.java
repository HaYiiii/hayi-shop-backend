package vn.hayi.shop.identity.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.identity.domain.IdentifierType;
import vn.hayi.shop.identity.domain.IdentityErrorCode;
import vn.hayi.shop.identity.domain.LoginIdentifier;
import vn.hayi.shop.identity.domain.LoginIdentifierRepository;
import vn.hayi.shop.identity.domain.MfaFactor;
import vn.hayi.shop.identity.domain.MfaFactorStatus;
import vn.hayi.shop.identity.domain.Totp;
import vn.hayi.shop.identity.domain.User;
import vn.hayi.shop.identity.domain.UserRepository;
import vn.hayi.shop.identity.infrastructure.SecurityEventLog;
import vn.hayi.shop.identity.infrastructure.SecurityEventType;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.Tokens;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;

/**
 * TOTP enrollment during login, for a user whose role requires MFA (Identity §2.4, API 01-security §6.2, Decision
 * D4, L2–L5). The enrollment token works until MFA is enabled; confirming the first code opens the session.
 */
@Service
public class MfaEnrollmentService {

	static final String ISSUER = "HaYi Shop";

	private final Tokens tokens;

	private final MfaCodes mfaCodes;

	private final UserRepository users;

	private final LoginIdentifierRepository identifiers;

	private final SessionIssuer sessionIssuer;

	private final SecurityEventLog securityEvents;

	private final Clock clock;

	MfaEnrollmentService(Tokens tokens, MfaCodes mfaCodes, UserRepository users, LoginIdentifierRepository identifiers,
			SessionIssuer sessionIssuer, SecurityEventLog securityEvents, Clock clock) {
		this.tokens = tokens;
		this.mfaCodes = mfaCodes;
		this.users = users;
		this.identifiers = identifiers;
		this.sessionIssuer = sessionIssuer;
		this.securityEvents = securityEvents;
		this.clock = clock;
	}

	/** A new pending secret; calling again replaces the pending one (Decision L4). */
	@Transactional
	public MfaEnrollment start(String enrollmentToken) {
		UUID userId = enrollingUser(enrollmentToken)
			.orElseThrow(() -> new DomainException(CommonErrorCode.AUTH_REQUIRED, "Invalid enrollment token"));
		List<MfaFactor> factors = this.mfaCodes.lockFactors(userId);
		if (factors.stream().anyMatch(factor -> factor.getStatus() == MfaFactorStatus.ACTIVE)) {
			throw new DomainException(CommonErrorCode.AUTH_REQUIRED, "MFA already enabled");
		}
		Instant now = Instant.now(this.clock);
		byte[] secret = Totp.newSecret();
		byte[] encrypted = this.mfaCodes.encrypt(userId, secret);
		factors.stream()
			.filter(factor -> factor.getStatus() == MfaFactorStatus.PENDING)
			.findFirst()
			.ifPresentOrElse(pending -> pending.replacePendingSecret(encrypted, now),
					() -> this.mfaCodes.save(MfaFactor.pendingTotp(userId, encrypted, now)));
		String base32 = Totp.base32(secret);
		return new MfaEnrollment(otpauthUri(accountName(userId), base32), base32);
	}

	/** The first code activates the factor, issues the recovery codes and opens the session. */
	@Transactional
	public MfaResult confirm(String enrollmentToken, String code, ClientInfo client) {
		Optional<UUID> found = enrollingUser(enrollmentToken);
		if (found.isEmpty()) {
			return new MfaResult.Rejected(CommonErrorCode.AUTH_REQUIRED);
		}
		UUID userId = found.get();
		List<MfaFactor> factors = this.mfaCodes.lockFactors(userId);
		Optional<MfaFactor> pending = factors.stream()
			.filter(factor -> factor.getStatus() == MfaFactorStatus.PENDING)
			.findFirst();
		if (pending.isEmpty() || factors.stream().anyMatch(factor -> factor.getStatus() == MfaFactorStatus.ACTIVE)) {
			return new MfaResult.Rejected(CommonErrorCode.AUTH_REQUIRED);
		}
		Instant now = Instant.now(this.clock);
		OptionalLong step = this.mfaCodes.matchPending(pending.get(), code, now);
		if (step.isEmpty()) {
			this.securityEvents.record(SecurityEventType.MFA_FAILED, userId, null, client);
			return new MfaResult.Rejected(IdentityErrorCode.MFA_CODE_INVALID);
		}
		pending.get().activate(Totp.stepStart(step.getAsLong()), now);
		List<String> recoveryCodes = this.mfaCodes.issueRecoveryCodes(userId, now);
		this.securityEvents.record(SecurityEventType.MFA_ENABLED, userId, null, client);
		return new MfaResult.Enrolled(this.sessionIssuer.openBackofficeSession(userId, client, now), recoveryCodes);
	}

	/** The token's user, if still active; the user row is locked like at login. */
	private Optional<UUID> enrollingUser(String enrollmentToken) {
		return this.tokens.verifyMfaEnrollmentToken(enrollmentToken)
			.flatMap(this.users::findByIdForUpdate)
			.filter(User::isActive)
			.map(User::getId);
	}

	private String accountName(UUID userId) {
		return this.identifiers.findByUserIdAndType(userId, IdentifierType.EMAIL)
			.map(LoginIdentifier::getNormalizedValue)
			.orElse(userId.toString());
	}

	/** Key URI format of Google Authenticator: {@code otpauth://totp/Issuer:account?secret=…&issuer=Issuer…}. */
	static String otpauthUri(String account, String base32Secret) {
		String issuer = encode(ISSUER);
		return "otpauth://totp/" + issuer + ":" + encode(account) + "?secret=" + base32Secret + "&issuer=" + issuer
				+ "&algorithm=SHA1&digits=6&period=30";
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

}
