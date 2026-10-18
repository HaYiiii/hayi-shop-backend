package vn.hayi.shop.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** TOTP secrets are stored AES-256-GCM encrypted under their own key, bound to their user (Decision L3). */
class MfaSecretCipherTest {

	private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

	private final MfaSecretCipher cipher = new MfaSecretCipher(KEY);

	private final UUID userId = UUID.randomUUID();

	@Test
	void decryptsWhatItEncrypted() {
		byte[] secret = "12345678901234567890".getBytes();

		byte[] encrypted = this.cipher.encrypt(this.userId, secret);

		assertThat(encrypted).isNotEqualTo(secret).hasSizeGreaterThan(secret.length);
		assertThat(this.cipher.decrypt(this.userId, encrypted)).isEqualTo(secret);
		assertThat(this.cipher.encrypt(this.userId, secret)).isNotEqualTo(encrypted);
	}

	@Test
	void refusesTamperedCiphertext() {
		byte[] encrypted = this.cipher.encrypt(this.userId, new byte[20]);
		encrypted[encrypted.length - 1] ^= 1;

		assertThatThrownBy(() -> this.cipher.decrypt(this.userId, encrypted)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void refusesASecretCopiedToAnotherUser() {
		byte[] encrypted = this.cipher.encrypt(this.userId, new byte[20]);

		assertThatThrownBy(() -> this.cipher.decrypt(UUID.randomUUID(), encrypted)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void requiresA256BitKey() {
		assertThatThrownBy(() -> new MfaSecretCipher(null)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("HAYI_SECURITY_MFA_ENCRYPTION_KEY");
		assertThatThrownBy(() -> new MfaSecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
			.isInstanceOf(IllegalStateException.class);
	}

}
