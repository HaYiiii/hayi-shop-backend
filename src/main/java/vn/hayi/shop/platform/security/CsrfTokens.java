package vn.hayi.shop.platform.security;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.hayi.shop.platform.persistence.UuidBinary;

/**
 * Double-submit CSRF tokens bound to a session (API 01-security §3.1): {@code base64(sid ‖ random)} + "." +
 * {@code base64(HMAC(sid ‖ random))}. A token minted for one session never validates for another, which prevents
 * fixation.
 */
@Component
public class CsrfTokens {

	public static final String COOKIE = "hy_csrf";

	public static final String HEADER = "X-CSRF-Token";

	/** Request attribute holding the session id a validated CSRF token belongs to. */
	public static final String VERIFIED_SESSION_ATTRIBUTE = CsrfTokens.class.getName() + ".session";

	private static final String PURPOSE = "csrf";

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

	private final SecureRandom random = new SecureRandom();

	private final SigningKeys keys;

	CsrfTokens(SigningKeys keys) {
		this.keys = keys;
	}

	public String issue(UUID sessionId) {
		byte[] nonce = new byte[16];
		this.random.nextBytes(nonce);
		byte[] payload = ByteBuffer.allocate(32).put(UuidBinary.toBytes(sessionId)).put(nonce).array();
		return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(this.keys.hmac(PURPOSE, payload));
	}

	/** The session a genuine token was issued for; empty when the token is malformed or forged. */
	public Optional<UUID> sessionOf(String token) {
		if (token == null) {
			return Optional.empty();
		}
		int dot = token.indexOf('.');
		if (dot < 0) {
			return Optional.empty();
		}
		try {
			byte[] payload = DECODER.decode(token.substring(0, dot));
			byte[] signature = DECODER.decode(token.substring(dot + 1));
			if (payload.length != 32 || !MessageDigest.isEqual(signature, this.keys.hmac(PURPOSE, payload))) {
				return Optional.empty();
			}
			byte[] sessionId = new byte[16];
			System.arraycopy(payload, 0, sessionId, 0, 16);
			return Optional.of(UuidBinary.fromBytes(sessionId));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

}
