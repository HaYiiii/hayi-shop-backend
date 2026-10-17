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
 */
@ConfigurationProperties("hayi.identity")
public record IdentityProperties(@DefaultValue Sessions backoffice, @DefaultValue Login login,
		@DefaultValue("10s") Duration refreshReuseGrace, @DefaultValue Bootstrap bootstrap) {

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

}
