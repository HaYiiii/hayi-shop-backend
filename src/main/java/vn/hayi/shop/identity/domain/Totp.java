package vn.hayi.shop.identity.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238, Decision L5): HMAC-SHA1, 6 digits, 30-second steps, one step of clock
 * drift either way. A step is accepted at most once per factor: codes up to the last used step are refused.
 */
public final class Totp {

	static final int DIGITS = 6;

	static final long STEP_SECONDS = 30;

	private static final int SECRET_BYTES = 20;

	private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

	private static final SecureRandom RANDOM = new SecureRandom();

	private Totp() {
	}

	/** A new 160-bit secret (RFC 4226 §4 recommends the HMAC-SHA1 output length). */
	public static byte[] newSecret() {
		byte[] secret = new byte[SECRET_BYTES];
		RANDOM.nextBytes(secret);
		return secret;
	}

	/**
	 * The step {@code code} belongs to, if it is valid at {@code now} (±1 step) and later than {@code lastUsedStep}.
	 * @param lastUsedStep start of the step last accepted for this secret, or {@code null}
	 */
	public static OptionalLong match(byte[] secret, String code, Instant now, Instant lastUsedStep) {
		if (code == null || code.length() != DIGITS || !code.chars().allMatch(Character::isDigit)) {
			return OptionalLong.empty();
		}
		long current = stepOf(now);
		long floor = (lastUsedStep != null) ? stepOf(lastUsedStep) : Long.MIN_VALUE;
		for (long step = current - 1; step <= current + 1; step++) {
			if (step > floor && MessageDigest.isEqual(codeOf(secret, step).getBytes(StandardCharsets.US_ASCII),
					code.getBytes(StandardCharsets.US_ASCII))) {
				return OptionalLong.of(step);
			}
		}
		return OptionalLong.empty();
	}

	public static String codeAt(byte[] secret, Instant time) {
		return codeOf(secret, stepOf(time));
	}

	public static long stepOf(Instant time) {
		return Math.floorDiv(time.getEpochSecond(), STEP_SECONDS);
	}

	public static Instant stepStart(long step) {
		return Instant.ofEpochSecond(step * STEP_SECONDS);
	}

	/** RFC 4648 base32 without padding, as authenticator apps expect in {@code otpauth://} URIs. */
	public static String base32(byte[] data) {
		StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
		int buffer = 0;
		int bits = 0;
		for (byte b : data) {
			buffer = (buffer << 8) | (b & 0xff);
			bits += 8;
			while (bits >= 5) {
				out.append(BASE32[(buffer >> (bits - 5)) & 31]);
				bits -= 5;
			}
		}
		if (bits > 0) {
			out.append(BASE32[(buffer << (5 - bits)) & 31]);
		}
		return out.toString();
	}

	private static String codeOf(byte[] secret, long step) {
		byte[] hash;
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(secret, "HmacSHA1"));
			hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
		int offset = hash[hash.length - 1] & 0x0f;
		int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
				| ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
		return String.format("%0" + DIGITS + "d", binary % 1_000_000);
	}

}
