package vn.hayi.shop.identity.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.platform.security.SigningKeys;

/**
 * Opaque refresh token values (Identity §2.6): {@code base64url(id ‖ HMAC(id))}. The database keeps only the
 * SHA-256; the value can be re-derived from the token id with the server key, which lets a concurrent refresh within
 * the grace window receive the pair already issued (API 01-security §5) without storing any plaintext.
 */
@Component
class RefreshTokenCodec {

	private static final String PURPOSE = "refresh-token";

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final SigningKeys keys;

	RefreshTokenCodec(SigningKeys keys) {
		this.keys = keys;
	}

	String encode(UUID tokenId) {
		byte[] id = UuidBinary.toBytes(tokenId);
		return ENCODER.encodeToString(ByteBuffer.allocate(48).put(id).put(this.keys.hmac(PURPOSE, id)).array());
	}

	/** Rejects forged or malformed values before touching the database. */
	Optional<String> verify(String token) {
		try {
			byte[] raw = Base64.getUrlDecoder().decode(token);
			if (raw.length != 48) {
				return Optional.empty();
			}
			byte[] id = Arrays.copyOfRange(raw, 0, 16);
			byte[] mac = Arrays.copyOfRange(raw, 16, 48);
			return MessageDigest.isEqual(mac, this.keys.hmac(PURPOSE, id)) ? Optional.of(token) : Optional.empty();
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
