package vn.hayi.shop.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import vn.hayi.shop.shared.id.UuidV7;

/**
 * A single-use recovery code (Identity §2.4, Decision L5): {@code XXXXX-XXXXX} in base32 (50 bits), stored as the
 * SHA-256 of its normalized form — a slow hash is not needed for random codes of this length.
 */
@Entity
@Table(name = "iam_mfa_recovery_code")
public class MfaRecoveryCode {

	/** Codes per user; issuing a new set replaces the old one. */
	public static final int SET_SIZE = 10;

	private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

	private static final SecureRandom RANDOM = new SecureRandom();

	@Id
	private UUID id;

	private UUID userId;

	private String codeHash;

	private Instant usedAt;

	@Column(updatable = false)
	private Instant createdAt;

	protected MfaRecoveryCode() {
	}

	public static MfaRecoveryCode of(UUID userId, String code, Instant now) {
		MfaRecoveryCode recoveryCode = new MfaRecoveryCode();
		recoveryCode.id = UuidV7.generate();
		recoveryCode.userId = userId;
		recoveryCode.codeHash = hash(code);
		recoveryCode.createdAt = now;
		return recoveryCode;
	}

	/** A new random code, shown to the user once. */
	public static String newCode() {
		StringBuilder code = new StringBuilder(11);
		for (int i = 0; i < 10; i++) {
			if (i == 5) {
				code.append('-');
			}
			code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
		}
		return code.toString();
	}

	/** Case, spaces and the dash are ignored, so the user may type the code either way. */
	public static String hash(String code) {
		String normalized = code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(normalized.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public void use(Instant now) {
		if (this.usedAt != null) {
			throw new IllegalStateException("Recovery code already used");
		}
		this.usedAt = now;
	}

}
