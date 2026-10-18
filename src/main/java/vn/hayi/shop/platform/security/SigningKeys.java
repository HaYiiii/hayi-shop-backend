package vn.hayi.shop.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * The server's single signing secret: the ES256 key that signs JWTs (API 01-security §4). HMAC keys for other
 * server-issued values (CSRF tokens, refresh tokens) are derived from it per purpose, so one secret is rotated and
 * a value issued for one purpose never verifies for another.
 */
public final class SigningKeys {

	private static final Logger LOG = LoggerFactory.getLogger(SigningKeys.class);

	private final ECKey jwk;

	private SigningKeys(ECKey jwk) {
		this.jwk = jwk;
	}

	static SigningKeys load(SecurityProperties properties) {
		if (StringUtils.hasText(properties.signingKey())) {
			try {
				ECKey key = ECKey.parse(properties.signingKey());
				if (!key.isPrivate() || !Curve.P_256.equals(key.getCurve()) || key.getKeyID() == null) {
					throw new IllegalStateException("hayi.security.signing-key must be a private P-256 JWK with a kid");
				}
				return new SigningKeys(key);
			}
			catch (ParseException ex) {
				throw new IllegalStateException("hayi.security.signing-key is not a valid JWK", ex);
			}
		}
		if (!properties.allowEphemeralKey()) {
			throw new IllegalStateException(
					"hayi.security.signing-key (HAYI_SECURITY_SIGNING_KEY) is required; set hayi.security.allow-ephemeral-key=true only for local/test");
		}
		LOG.warn("Using an ephemeral JWT signing key: sessions do not survive a restart (local/test only)");
		try {
			return new SigningKeys(new ECKeyGenerator(Curve.P_256).keyUse(KeyUse.SIGNATURE)
				.keyID(UUID.randomUUID().toString()).generate());
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("Cannot generate an ephemeral signing key", ex);
		}
	}

	ECKey jwk() {
		return this.jwk;
	}

	/** HMAC-SHA256 of {@code data} under a key derived for {@code purpose}. */
	public byte[] hmac(String purpose, byte[] data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(derivedKey(purpose), "HmacSHA256"));
			return mac.doFinal(data);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private byte[] derivedKey(String purpose) throws GeneralSecurityException {
		Mac mac = Mac.getInstance("HmacSHA256");
		try {
			mac.init(new SecretKeySpec(this.jwk.toECPrivateKey().getS().toByteArray(), "HmacSHA256"));
		}
		catch (JOSEException ex) {
			throw new GeneralSecurityException(ex);
		}
		return mac.doFinal(purpose.getBytes(StandardCharsets.UTF_8));
	}

}
