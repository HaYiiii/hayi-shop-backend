package vn.hayi.shop.identity.application;

import java.time.Duration;

/** What the browser receives for a live session: access JWT, refresh token and the session-bound CSRF token. */
public record SessionTokens(String accessToken, String refreshToken, Duration refreshLifetime, String csrfToken) {
}
