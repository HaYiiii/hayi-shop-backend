package vn.hayi.shop.identity.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import vn.hayi.shop.identity.domain.LoginLockPolicy;

/**
 * Session and login settings (API 01-security §5, §6.3).
 *
 * @param backoffice Backoffice session lifetimes
 * @param login temporary lock after repeated failures
 * @param refreshReuseGrace reuse of a just-rotated refresh token within this window is a concurrent refresh, not theft
 * @param bootstrap one-time Owner bootstrap (Decision K1)
 * @param mfa MFA challenge and step-up limits (Decision L1, L7)
 */
@ConfigurationProperties("hayi.identity")
public record IdentityProperties(@DefaultValue Sessions backoffice, @DefaultValue Login login,
		@DefaultValue("10s") Duration refreshReuseGrace, @DefaultValue Bootstrap bootstrap, @DefaultValue Mfa mfa) {

	/**
	 * @param refreshIdleTtl a refresh token unused this long expires
	 * @param absoluteLifetime the session ends this long after login, whatever the activity
	 */
	public record Sessions(@DefaultValue("12h") Duration refreshIdleTtl, @DefaultValue("7d") Duration absoluteLifetime) {
	}

	public record Login(@DefaultValue("5") int maxFailures, @DefaultValue("15m") Duration failureWindow,
			@DefaultValue("15m") Duration lockDuration) {

		/** Validated at binding, so a wrong configuration stops the application from starting. */
		public Login {
			new LoginLockPolicy(maxFailures, failureWindow, lockDuration);
		}

		public LoginLockPolicy lockPolicy() {
			return new LoginLockPolicy(this.maxFailures, this.failureWindow, this.lockDuration);
		}

	}

	public record Bootstrap(String ownerEmail, String ownerPassword) {
	}

	/**
	 * @param challengeTtl the MFA step of a login must be answered within this time
	 * @param challengeMaxAttempts wrong codes that lock a challenge
	 * @param stepUpMaxFailures failed step-ups within {@code stepUpFailureWindow} that revoke the session
	 * @param loginMaxFailures wrong login MFA codes of a user, across challenges, that lock the login
	 */
	public record Mfa(@DefaultValue("5m") Duration challengeTtl, @DefaultValue("5") int challengeMaxAttempts,
			@DefaultValue("5") int stepUpMaxFailures, @DefaultValue("15m") Duration stepUpFailureWindow,
			@DefaultValue("10") int loginMaxFailures, @DefaultValue("15m") Duration loginFailureWindow,
			@DefaultValue("15m") Duration loginLockDuration) {

		/** Validated at binding, so a wrong configuration stops the application from starting. */
		public Mfa {
			new LoginLockPolicy(loginMaxFailures, loginFailureWindow, loginLockDuration);
		}

		public LoginLockPolicy loginLockPolicy() {
			return new LoginLockPolicy(this.loginMaxFailures, this.loginFailureWindow, this.loginLockDuration);
		}

	}

}
