package vn.hayi.shop.platform.security;

import java.io.IOException;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

import vn.hayi.shop.shared.error.CommonErrorCode;

/**
 * Security entry point for every surface (API README §2, 01-security). Public surface, health probes and the auth
 * endpoints that run before a session are open; everything else needs the surface's access cookie. Each request's
 * session is checked live (Decision G1), and unsafe cookie-authenticated requests need the CSRF header (§3.1).
 * Security errors are rendered as Problem Details by delegating to the MVC exception handling, so they share one
 * format with every other error.
 * <p>
 * Spring's own CSRF support is off: HaYi uses a stateless, session-bound double-submit token ({@link CsrfFilter}).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfig {

	@Bean
	SigningKeys signingKeys(SecurityProperties properties) {
		return SigningKeys.load(properties);
	}

	/** Argon2id for new hashes (ERD 01 {@code iam_user_credential}); the {@code {id}} prefix keeps hashes upgradable. */
	@Bean
	PasswordEncoder passwordEncoder() {
		DelegatingPasswordEncoder encoder = (DelegatingPasswordEncoder) PasswordEncoderFactories
			.createDelegatingPasswordEncoder();
		Map<String, PasswordEncoder> encoders = Map.of("argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
		DelegatingPasswordEncoder argon2 = new DelegatingPasswordEncoder("argon2", encoders);
		argon2.setDefaultPasswordEncoderForMatches(encoder);
		return argon2;
	}

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, Tokens tokens, SessionStatusChecker sessions,
			CsrfTokens csrfTokens, SecurityEventSink securityEvents,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(requests -> requests
				.requestMatchers("/api/public/**", "/actuator/health", "/actuator/health/**", "/error").permitAll()
				.requestMatchers(AuthEndpoints.PUBLIC_PATTERNS).permitAll()
				.anyRequest().authenticated())
			.oauth2ResourceServer(resourceServer -> resourceServer
				.bearerTokenResolver(new CookieBearerTokenResolver())
				.authenticationManagerResolver(accessTokenAuthentication(tokens, sessions))
				.authenticationEntryPoint((request, response, ex) -> renderProblem(exceptionResolver, request, response, ex, HttpServletResponse.SC_UNAUTHORIZED)))
			.addFilterAfter(new CsrfFilter(csrfTokens, securityEvents, exceptionResolver), BearerTokenAuthenticationFilter.class)
			.exceptionHandling(errors -> errors
				.authenticationEntryPoint((request, response, ex) -> renderProblem(exceptionResolver, request, response, ex, HttpServletResponse.SC_UNAUTHORIZED))
				.accessDeniedHandler((request, response, ex) -> renderProblem(exceptionResolver, request, response, ex, HttpServletResponse.SC_FORBIDDEN)))
			.build();
	}

	/**
	 * One verifier per surface, so a token is only accepted where its {@code aud} matches (API 01-security §4), and a
	 * token of a revoked or expired session is refused immediately ({@code AUTH_SESSION_REVOKED}).
	 */
	private static AuthenticationManagerResolver<HttpServletRequest> accessTokenAuthentication(Tokens tokens,
			SessionStatusChecker sessions) {
		Map<Surface, AuthenticationManager> managers = new EnumMap<>(Surface.class);
		for (Surface surface : Surface.values()) {
			JwtAuthenticationProvider provider = new JwtAuthenticationProvider(tokens.accessTokenDecoder(surface));
			provider.setJwtAuthenticationConverter(jwt -> {
				AuthenticatedSession session = Tokens.sessionOf(jwt);
				if (!sessions.isActive(session.sessionId())) {
					throw new CodedAuthenticationException(CommonErrorCode.AUTH_SESSION_REVOKED, "Session is revoked or expired");
				}
				return new SessionAuthentication(session, jwt.getTokenValue());
			});
			managers.put(surface, new ProviderManager(provider));
		}
		return request -> Surface.of(AuthEndpoints.pathOf(request))
			.map(managers::get)
			.orElse(authentication -> {
				throw new CodedAuthenticationException(CommonErrorCode.AUTH_REQUIRED, "No session on this surface");
			});
	}

	/** Never leaves the client with an empty 200: if no handler took the exception, fall back to the bare status. */
	private static void renderProblem(HandlerExceptionResolver resolver, HttpServletRequest request,
			HttpServletResponse response, Exception ex, int fallbackStatus) throws IOException {
		if (resolver.resolveException(request, response, null, ex) == null) {
			response.sendError(fallbackStatus);
		}
	}

}
