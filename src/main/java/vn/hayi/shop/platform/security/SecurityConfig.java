package vn.hayi.shop.platform.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Security entry point for every surface (API README §2). Public surface and health probes are open; all other
 * requests need an authenticated session. Security errors are rendered as Problem Details by delegating to the
 * MVC exception handling, so they share one format with every other error.
 * <p>
 * CSRF is off here on purpose: the double-submit token ({@code hy_csrf}, API 01-security) arrives with login (#13).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(requests -> requests
				.requestMatchers("/api/public/**", "/actuator/health", "/actuator/health/**", "/error").permitAll()
				.anyRequest().authenticated())
			.exceptionHandling(errors -> errors
				.authenticationEntryPoint((request, response, ex) -> renderProblem(exceptionResolver, request, response, ex, HttpServletResponse.SC_UNAUTHORIZED))
				.accessDeniedHandler((request, response, ex) -> renderProblem(exceptionResolver, request, response, ex, HttpServletResponse.SC_FORBIDDEN)))
			.build();
	}

	/** Never leaves the client with an empty 200: if no handler took the exception, fall back to the bare status. */
	private static void renderProblem(HandlerExceptionResolver resolver, HttpServletRequest request,
			HttpServletResponse response, Exception ex, int fallbackStatus) throws IOException {
		if (resolver.resolveException(request, response, null, ex) == null) {
			response.sendError(fallbackStatus);
		}
	}

}
