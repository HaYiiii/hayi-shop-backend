package vn.hayi.shop.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.identity.domain.IdentityErrorCode;
import vn.hayi.shop.identity.domain.RevokeReason;
import vn.hayi.shop.identity.domain.UserSession;
import vn.hayi.shop.identity.domain.UserSessionRepository;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.shared.actor.Persona;
import vn.hayi.shop.shared.error.DomainException;

/**
 * The devices a user is signed in on, per surface (Identity §2.5, API 01-security §6.1–6.2, Decision AT): only the
 * caller's own live sessions of that surface's persona.
 */
@Service
public class DeviceSessionService {

	private final UserSessionRepository sessions;

	private final SessionService sessionService;

	private final Clock clock;

	DeviceSessionService(UserSessionRepository sessions, SessionService sessionService, Clock clock) {
		this.sessions = sessions;
		this.sessionService = sessionService;
		this.clock = clock;
	}

	public record Device(UUID sessionId, String userAgent, String ipAddress, Instant createdAt, Instant lastActiveAt,
			boolean current) {
	}

	/** Live sessions of {@code persona}, most recently used first (AT1). */
	@Transactional(readOnly = true)
	public List<Device> list(UUID userId, Persona persona, UUID currentSessionId) {
		Instant now = this.clock.instant();
		return this.sessions.findByUserIdAndPersonaAndRevokedAtIsNull(userId, persona)
			.stream()
			.filter(session -> session.isActive(now))
			.sorted(Comparator.comparing(UserSession::getLastActiveAt).reversed())
			.map(session -> new Device(session.getId(), session.getUserAgent(), session.getIpAddress(),
					session.getCreatedAt(), session.getLastActiveAt(), session.getId().equals(currentSessionId)))
			.toList();
	}

	/**
	 * Ends another of the caller's sessions now; the cache is evicted after commit, so its next request gets 401 (AT2).
	 * @throws DomainException {@code SESSION_NOT_FOUND}, {@code SESSION_IS_CURRENT}
	 */
	@Transactional
	public void revoke(UUID userId, Persona persona, UUID currentSessionId, UUID targetId, ClientInfo client) {
		if (targetId.equals(currentSessionId)) {
			throw new DomainException(IdentityErrorCode.SESSION_IS_CURRENT,
					"Use /auth/logout to end the session in use");
		}
		Instant now = this.clock.instant();
		UserSession target = this.sessions.findByIdForUpdate(targetId)
			.filter(session -> session.getUserId().equals(userId) && session.getPersona() == persona)
			.filter(session -> session.isActive(now))
			.orElseThrow(() -> new DomainException(IdentityErrorCode.SESSION_NOT_FOUND, "No session " + targetId));
		this.sessionService.revoke(target, RevokeReason.USER_REVOKED, now, client);
	}

}
