package vn.hayi.shop.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.identity.domain.RefreshToken;
import vn.hayi.shop.identity.domain.RefreshTokenRepository;
import vn.hayi.shop.identity.domain.RevokeReason;
import vn.hayi.shop.identity.domain.UserSession;
import vn.hayi.shop.identity.domain.UserSessionRepository;
import vn.hayi.shop.identity.infrastructure.CachedSessionStatus;
import vn.hayi.shop.identity.infrastructure.SecurityEventLog;
import vn.hayi.shop.identity.infrastructure.SecurityEventType;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.shared.error.CommonErrorCode;

/**
 * Refresh-token rotation and logout (Identity §2.5–2.6, API 01-security §5). One session is one refresh-token
 * family. The presented token and its session are read with row locks, so two concurrent refreshes of one token
 * run one after the other and the second sees the rotation.
 */
@Service
public class SessionService {

	private final RefreshTokenRepository refreshTokens;

	private final UserSessionRepository sessions;

	private final RefreshTokenCodec codec;

	private final SessionIssuer sessionIssuer;

	private final CachedSessionStatus sessionStatus;

	private final SecurityEventLog securityEvents;

	private final IdentityProperties properties;

	private final Clock clock;

	SessionService(RefreshTokenRepository refreshTokens, UserSessionRepository sessions, RefreshTokenCodec codec,
			SessionIssuer sessionIssuer, CachedSessionStatus sessionStatus, SecurityEventLog securityEvents,
			IdentityProperties properties, Clock clock) {
		this.refreshTokens = refreshTokens;
		this.sessions = sessions;
		this.codec = codec;
		this.sessionIssuer = sessionIssuer;
		this.sessionStatus = sessionStatus;
		this.securityEvents = securityEvents;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Rotates {@code refreshToken}. A token already rotated is reuse: within the grace window the caller gets the
	 * family's current token again; later, the whole session is revoked ({@code REFRESH_TOKEN_REUSE_DETECTED}).
	 * @param csrfSessionId session the request's CSRF token belongs to; must be this token's session
	 */
	@Transactional
	public RefreshResult refresh(String refreshToken, UUID csrfSessionId, ClientInfo client) {
		Instant now = Instant.now(this.clock);
		Optional<RefreshToken> found = find(refreshToken);
		if (found.isEmpty()) {
			return new RefreshResult.Rejected(CommonErrorCode.AUTH_REQUIRED);
		}
		UserSession session = this.sessions.findByIdForUpdate(found.get().getSessionId()).orElseThrow();
		if (!session.getId().equals(csrfSessionId)) {
			this.securityEvents.csrfValidationFailed(session.getUserId(), session.getId(), client);
			return new RefreshResult.Rejected(CommonErrorCode.CSRF_INVALID);
		}
		if (!session.isActive(now)) {
			return new RefreshResult.Rejected(CommonErrorCode.AUTH_SESSION_REVOKED);
		}
		RefreshToken presented = found.get();
		if (presented.isRotated()) {
			if (presented.isWithinReuseGrace(now, this.properties.refreshReuseGrace())) {
				return new RefreshResult.Rotated(this.sessionIssuer.tokensFor(session, latestOf(presented), now));
			}
			revoke(session, RevokeReason.REUSE_DETECTED, now, client);
			this.securityEvents.record(SecurityEventType.REFRESH_TOKEN_REUSE_DETECTED, session.getUserId(), session.getId(), client);
			return new RefreshResult.Rejected(CommonErrorCode.AUTH_SESSION_REVOKED);
		}
		if (presented.isExpired(now)) {
			return new RefreshResult.Rejected(CommonErrorCode.AUTH_REQUIRED);
		}
		RefreshToken replacement = this.sessionIssuer.newRefreshToken(session, now);
		presented.rotate(replacement.getId(), now);
		session.touch(now);
		return new RefreshResult.Rotated(this.sessionIssuer.tokensFor(session, replacement, now));
	}

	/**
	 * Revokes the session of {@code refreshToken}, or else the session the verified CSRF token was minted for (the
	 * access cookie is not read on auth endpoints, and the refresh cookie may be gone). Idempotent.
	 * @return {@code false} when the CSRF token belongs to another session
	 */
	@Transactional
	public boolean logout(String refreshToken, UUID csrfSessionId, ClientInfo client) {
		Optional<UserSession> found = find(refreshToken).map(RefreshToken::getSessionId)
			.or(() -> Optional.ofNullable(csrfSessionId))
			.flatMap(this.sessions::findByIdForUpdate);
		if (found.isEmpty()) {
			return true;
		}
		UserSession session = found.get();
		if (!session.getId().equals(csrfSessionId)) {
			this.securityEvents.csrfValidationFailed(session.getUserId(), session.getId(), client);
			return false;
		}
		revoke(session, RevokeReason.LOGOUT, Instant.now(this.clock), client);
		return true;
	}

	private Optional<RefreshToken> find(String refreshToken) {
		return Optional.ofNullable(refreshToken)
			.flatMap(this.codec::verify)
			.flatMap(token -> this.refreshTokens.findByTokenHash(RefreshTokenCodec.hash(token)));
	}

	/** Follows the rotation chain to the family's current token. */
	private RefreshToken latestOf(RefreshToken token) {
		RefreshToken current = token;
		while (current.getReplacedById() != null) {
			current = this.refreshTokens.findById(current.getReplacedById()).orElseThrow();
		}
		return current;
	}

	private void revoke(UserSession session, RevokeReason reason, Instant now, ClientInfo client) {
		if (session.revoke(reason, now)) {
			this.sessionStatus.evictAfterCommit(session.getId());
			this.securityEvents.record(SecurityEventType.SESSION_REVOKED, session.getUserId(), session.getId(), client);
		}
	}

}
