package vn.hayi.shop.identity.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.hayi.shop.identity.domain.RefreshToken;
import vn.hayi.shop.identity.domain.RefreshTokenRepository;
import vn.hayi.shop.identity.domain.UserSession;
import vn.hayi.shop.platform.security.CsrfTokens;
import vn.hayi.shop.platform.security.Surface;
import vn.hayi.shop.platform.security.Tokens;
import vn.hayi.shop.shared.id.UuidV7;

/** Mints the token set of a session: a new refresh token of its family plus access and CSRF tokens. */
@Component
class SessionIssuer {

	private final RefreshTokenRepository refreshTokens;

	private final RefreshTokenCodec codec;

	private final Tokens tokens;

	private final CsrfTokens csrfTokens;

	private final IdentityProperties properties;

	SessionIssuer(RefreshTokenRepository refreshTokens, RefreshTokenCodec codec, Tokens tokens, CsrfTokens csrfTokens,
			IdentityProperties properties) {
		this.refreshTokens = refreshTokens;
		this.codec = codec;
		this.tokens = tokens;
		this.csrfTokens = csrfTokens;
		this.properties = properties;
	}

	/** A new refresh token, idle-limited and never outliving the session. */
	RefreshToken newRefreshToken(UserSession session, Instant now) {
		UUID id = UuidV7.generate();
		Instant idleExpiry = now.plus(this.properties.backoffice().refreshIdleTtl());
		Instant expiresAt = idleExpiry.isBefore(session.getExpiresAt()) ? idleExpiry : session.getExpiresAt();
		return this.refreshTokens.save(RefreshToken.issue(id, session.getId(), RefreshTokenCodec.hash(this.codec.encode(id)), now, expiresAt));
	}

	SessionTokens tokensFor(UserSession session, RefreshToken refreshToken, Instant now) {
		return new SessionTokens(
				this.tokens.issueAccessToken(Surface.BACKOFFICE, session.getUserId(), session.getId()),
				this.codec.encode(refreshToken.getId()), Duration.between(now, refreshToken.getExpiresAt()),
				this.csrfTokens.issue(session.getId()));
	}

}
