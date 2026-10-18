package vn.hayi.shop.identity.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.hayi.shop.identity.domain.RefreshToken;
import vn.hayi.shop.identity.domain.RefreshTokenRepository;
import vn.hayi.shop.identity.domain.SessionChannel;
import vn.hayi.shop.identity.domain.UserSession;
import vn.hayi.shop.identity.domain.UserSessionRepository;
import vn.hayi.shop.identity.infrastructure.SecurityEventLog;
import vn.hayi.shop.identity.infrastructure.SecurityEventType;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.CsrfTokens;
import vn.hayi.shop.platform.security.Surface;
import vn.hayi.shop.platform.security.Tokens;
import vn.hayi.shop.shared.actor.Persona;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * Opens sessions and mints their token set: a new refresh token of the family plus access and CSRF tokens.
 */
@Component
class SessionIssuer {

	private final UserSessionRepository sessions;

	private final RefreshTokenRepository refreshTokens;

	private final RefreshTokenCodec codec;

	private final Tokens tokens;

	private final CsrfTokens csrfTokens;

	private final IdentityProperties properties;

	private final SecurityEventLog securityEvents;

	SessionIssuer(UserSessionRepository sessions, RefreshTokenRepository refreshTokens, RefreshTokenCodec codec,
			Tokens tokens, CsrfTokens csrfTokens, IdentityProperties properties, SecurityEventLog securityEvents) {
		this.sessions = sessions;
		this.refreshTokens = refreshTokens;
		this.codec = codec;
		this.tokens = tokens;
		this.csrfTokens = csrfTokens;
		this.properties = properties;
		this.securityEvents = securityEvents;
	}

	/** A new Backoffice session for a user who passed every login step (password, MFA). */
	SessionTokens openBackofficeSession(UUID userId, ClientInfo client, Instant now) {
		UserSession session = this.sessions.save(UserSession.open(userId, Persona.STAFF, SessionChannel.BACKOFFICE_WEB,
				client.ipAddress(), client.userAgent(), now, this.properties.backoffice().absoluteLifetime()));
		RefreshToken refreshToken = newRefreshToken(session, now);
		this.securityEvents.record(SecurityEventType.SESSION_CREATED, userId, session.getId(), client);
		return tokensFor(session, refreshToken, now);
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
