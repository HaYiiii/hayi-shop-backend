package vn.hayi.shop.platform.security;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import vn.hayi.shop.shared.actor.Persona;

/** The caller of the current request, as carried by the access token (Identity §2.6). */
public record AuthenticatedSession(UUID userId, UUID sessionId, Persona persona) {

	public static Optional<AuthenticatedSession> current() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof SessionAuthentication session) {
			return Optional.of(session.getPrincipal());
		}
		return Optional.empty();
	}

}
