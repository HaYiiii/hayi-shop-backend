package vn.hayi.shop.platform.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import vn.hayi.shop.shared.actor.Persona;

/**
 * Issues and verifies the server's JWTs (API 01-security §4, Decision G1, K3): ES256 with {@code kid}, carrying only
 * {@code sub} (userId), {@code sid} (sessionId) and {@code per} (persona) — never roles or permissions.
 */
@Component
public class Tokens {

	/** {@code aud} of the single-purpose token used by {@code POST /auth/password/first-change} (Decision K3). */
	static final String PASSWORD_CHANGE_AUDIENCE = "hayi-backoffice-password-change";

	/** {@code aud} of the single-purpose token used by {@code POST /auth/mfa/enroll*} (Decision L2). */
	static final String MFA_ENROLLMENT_AUDIENCE = "hayi-backoffice-mfa-enrollment";

	/** Error code of an expired JWT; surfaces as {@code AUTH_TOKEN_EXPIRED} rather than {@code AUTH_REQUIRED}. */
	static final String EXPIRED = "token_expired";

	private final SigningKeys keys;

	private final SecurityProperties properties;

	private final Clock clock;

	private final JwtEncoder encoder;

	private final JwtDecoder passwordChangeDecoder;

	private final JwtDecoder mfaEnrollmentDecoder;

	Tokens(SigningKeys keys, SecurityProperties properties, Clock clock) {
		this.keys = keys;
		this.properties = properties;
		this.clock = clock;
		this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.jwk())));
		this.passwordChangeDecoder = decoder(PASSWORD_CHANGE_AUDIENCE);
		this.mfaEnrollmentDecoder = decoder(MFA_ENROLLMENT_AUDIENCE);
	}

	/** Short-lived access token for a session on {@code surface}; expires with the access cookie. */
	public String issueAccessToken(Surface surface, UUID userId, UUID sessionId) {
		Instant now = Instant.now(this.clock);
		return encode(JwtClaimsSet.builder()
			.issuer(this.properties.issuer())
			.audience(List.of(surface.audience()))
			.subject(userId.toString())
			.claim("sid", sessionId.toString())
			.claim("per", surface.persona().name())
			.issuedAt(now)
			.expiresAt(now.plus(this.properties.accessTokenTtl()))
			.id(UUID.randomUUID().toString())
			.build());
	}

	/** Lets the holder of a temporary password set a new one, and nothing else (Decision K3). */
	public String issuePasswordChangeToken(UUID userId) {
		return issueSinglePurpose(PASSWORD_CHANGE_AUDIENCE, userId, this.properties.passwordChangeTokenTtl());
	}

	/** The user a valid, unexpired password-change token was issued to. */
	public Optional<UUID> verifyPasswordChangeToken(String token) {
		return subjectOf(this.passwordChangeDecoder, token);
	}

	/** Lets a user whose role requires MFA enroll TOTP, and nothing else (Decision L2). */
	public String issueMfaEnrollmentToken(UUID userId) {
		return issueSinglePurpose(MFA_ENROLLMENT_AUDIENCE, userId, this.properties.mfaEnrollmentTokenTtl());
	}

	/** The user a valid, unexpired MFA enrollment token was issued to. */
	public Optional<UUID> verifyMfaEnrollmentToken(String token) {
		return subjectOf(this.mfaEnrollmentDecoder, token);
	}

	private String issueSinglePurpose(String audience, UUID userId, Duration ttl) {
		Instant now = Instant.now(this.clock);
		return encode(JwtClaimsSet.builder()
			.issuer(this.properties.issuer())
			.audience(List.of(audience))
			.subject(userId.toString())
			.issuedAt(now)
			.expiresAt(now.plus(ttl))
			.id(UUID.randomUUID().toString())
			.build());
	}

	private static Optional<UUID> subjectOf(JwtDecoder decoder, String token) {
		try {
			return Optional.of(UUID.fromString(decoder.decode(token).getSubject()));
		}
		catch (JwtException | IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	/** Verifies signature, {@code iss}, {@code aud} = surface and expiry against the application clock. */
	JwtDecoder accessTokenDecoder(Surface surface) {
		return decoder(surface.audience());
	}

	static AuthenticatedSession sessionOf(Jwt jwt) {
		return new AuthenticatedSession(UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")),
				Persona.valueOf(jwt.getClaimAsString("per")));
	}

	private String encode(JwtClaimsSet claims) {
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(this.keys.jwk().getKeyID()).build();
		return this.encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	private JwtDecoder decoder(String audience) {
		DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
		processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256,
				new ImmutableJWKSet<>(new JWKSet(this.keys.jwk().toPublicJWK()))));
		// Claims are validated below with the application clock, not by Nimbus.
		processor.setJWTClaimsSetVerifier((claims, context) -> {
		});
		NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtIssuerValidator(this.properties.issuer()),
				new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)),
				notExpired()));
		return decoder;
	}

	private OAuth2TokenValidator<Jwt> notExpired() {
		return jwt -> {
			Instant expiresAt = jwt.getExpiresAt();
			if (expiresAt == null || !Instant.now(this.clock).isBefore(expiresAt)) {
				return OAuth2TokenValidatorResult.failure(new OAuth2Error(EXPIRED, "Jwt expired", null));
			}
			return OAuth2TokenValidatorResult.success();
		};
	}

}
