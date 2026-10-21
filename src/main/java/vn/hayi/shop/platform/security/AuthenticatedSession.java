package vn.hayi.shop.platform.security;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import vn.hayi.shop.shared.actor.Persona;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;

/** The caller of the current request, as carried by the access token (Identity §2.6). */
public record AuthenticatedSession(UUID userId, UUID sessionId, Persona persona) {

	public static Optional<AuthenticatedSession> current() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof SessionAuthentication session) {
			return Optional.of(session.getPrincipal());
		}
		return Optional.empty();
	}

	/** The caller of a request that must have a session, else {@code 401 AUTH_REQUIRED}. */
	public static AuthenticatedSession require() {
		return current().orElseThrow(() -> new DomainException(CommonErrorCode.AUTH_REQUIRED, "No session"));
	}

}
