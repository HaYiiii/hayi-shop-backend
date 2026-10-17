package vn.hayi.shop.platform.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Token settings (API 01-security §4–5, Decision G1, K3).
 *
 * @param issuer {@code iss} of every JWT
 * @param signingKey ES256 private key as a JWK (JSON, with {@code kid}); comes from the secret manager / env
 * @param allowEphemeralKey local/test only: generate a throw-away key when {@code signingKey} is empty
 * @param accessTokenTtl access JWT lifetime (also the {@code Max-Age} of the access cookie)
 * @param passwordChangeTokenTtl lifetime of the {@code changeToken} for the first password change
 */
@ConfigurationProperties("hayi.security")
public record SecurityProperties(@DefaultValue("https://api.hayi.vn") String issuer, String signingKey,
		@DefaultValue("false") boolean allowEphemeralKey, @DefaultValue("10m") Duration accessTokenTtl,
		@DefaultValue("5m") Duration passwordChangeTokenTtl) {
}
