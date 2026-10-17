package vn.hayi.shop.identity.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.identity.domain.PasswordPolicy;
import vn.hayi.shop.identity.domain.UserCredential;
import vn.hayi.shop.identity.domain.UserCredentialRepository;
import vn.hayi.shop.identity.infrastructure.SecurityEventLog;
import vn.hayi.shop.identity.infrastructure.SecurityEventType;
import vn.hayi.shop.platform.security.Tokens;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;

/**
 * Replaces a temporary password (Identity §2.11, Decision K3–K4). Creates no session: the user logs in again with
 * the new password. The change token works once, because the credential stops requiring a change.
 */
@Service
public class FirstPasswordChangeService {

	private final Tokens tokens;

	private final UserCredentialRepository credentials;

	private final PasswordEncoder passwordEncoder;

	private final SecurityEventLog securityEvents;

	private final Clock clock;

	FirstPasswordChangeService(Tokens tokens, UserCredentialRepository credentials, PasswordEncoder passwordEncoder,
			SecurityEventLog securityEvents, Clock clock) {
		this.tokens = tokens;
		this.credentials = credentials;
		this.passwordEncoder = passwordEncoder;
		this.securityEvents = securityEvents;
		this.clock = clock;
	}

	@Transactional
	public void change(String changeToken, String newPassword, ClientInfo client) {
		UserCredential credential = this.tokens.verifyPasswordChangeToken(changeToken)
			.flatMap(this.credentials::findByIdForUpdate)
			.filter(UserCredential::isMustChangePassword)
			.orElseThrow(() -> new DomainException(CommonErrorCode.AUTH_REQUIRED, "Invalid or used change token"));
		PasswordPolicy.check(newPassword);
		if (this.passwordEncoder.matches(newPassword, credential.getPasswordHash())) {
			throw PasswordPolicy.violation();
		}
		credential.changePassword(this.passwordEncoder.encode(newPassword), Instant.now(this.clock));
		this.securityEvents.record(SecurityEventType.PASSWORD_CHANGED, credential.getUserId(), null, client);
	}

}
