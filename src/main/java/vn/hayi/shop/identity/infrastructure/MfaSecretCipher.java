package vn.hayi.shop.identity.infrastructure;

import static vn.hayi.shop.platform.persistence.UuidBinary.toBytes;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Encrypts TOTP secrets for {@code iam_mfa_factor.secret_encrypted} (ERD 01, Decision L3): AES-256-GCM under a key
 * of its own, never derived from the JWT signing key. The user id is authenticated data, so a secret copied to
 * another user's row does not decrypt. Stored value: 12-byte nonce ‖ ciphertext ‖ 16-byte tag.
 */
@Component
public class MfaSecretCipher {

	private static final int NONCE_BYTES = 12;

	private static final int TAG_BITS = 128;

	private static final SecureRandom RANDOM = new SecureRandom();

	private final SecretKeySpec key;

	MfaSecretCipher(@Value("${hayi.security.mfa-encryption-key:}") String base64Key) {
		if (!StringUtils.hasText(base64Key)) {
			throw new IllegalStateException(
					"hayi.security.mfa-encryption-key (HAYI_SECURITY_MFA_ENCRYPTION_KEY) is required: 32 random bytes, base64");
		}
		byte[] bytes = Base64.getDecoder().decode(base64Key.strip());
		if (bytes.length != 32) {
			throw new IllegalStateException("hayi.security.mfa-encryption-key must be 32 bytes (AES-256), base64");
		}
		this.key = new SecretKeySpec(bytes, "AES");
	}

	public byte[] encrypt(UUID userId, byte[] secret) {
		byte[] nonce = new byte[NONCE_BYTES];
		RANDOM.nextBytes(nonce);
		try {
			Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, userId);
			byte[] sealed = cipher.doFinal(secret);
			return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Cannot encrypt the MFA secret", ex);
		}
	}

	public byte[] decrypt(UUID userId, byte[] stored) {
		try {
			Cipher cipher = cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(stored, NONCE_BYTES), userId);
			return cipher.doFinal(stored, NONCE_BYTES, stored.length - NONCE_BYTES);
		}
		catch (GeneralSecurityException | IllegalArgumentException ex) {
			throw new IllegalStateException("Cannot decrypt the MFA secret (wrong key or tampered value)", ex);
		}
	}

	private Cipher cipher(int mode, byte[] nonce, UUID userId) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, this.key, new GCMParameterSpec(TAG_BITS, nonce));
		cipher.updateAAD(toBytes(userId));
		return cipher;
	}

}
