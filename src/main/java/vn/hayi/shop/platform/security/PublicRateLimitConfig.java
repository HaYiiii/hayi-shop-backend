package vn.hayi.shop.platform.security;

import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Limits {@code /api/public/v1} per IP (Decision W9); Backoffice and Customer surfaces are not limited here. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PublicRateLimitProperties.class)
class PublicRateLimitConfig implements WebMvcConfigurer {

	private final PublicRateLimitInterceptor interceptor;

	PublicRateLimitConfig(PublicRateLimitProperties properties, SecurityEventSink events, Clock clock) {
		this.interceptor = new PublicRateLimitInterceptor(properties,
				new ClientIpResolver(properties.trustedProxies()),
				new FixedWindowRateLimiter(Duration.ofMinutes(1), properties.maxTrackedClients(), clock), events);
	}

	/** The client IP behind the trusted proxies, for any check that counts per IP (W9, Decision AN5). */
	@Bean
	ClientIpResolver clientIpResolver(PublicRateLimitProperties properties) {
		return new ClientIpResolver(properties.trustedProxies());
	}

	/** Runs ahead of Spring Security so the CSRF filter's events see the resolved IP too (Decision AU3). */
	@Bean
	FilterRegistrationBean<ClientIpFilter> clientIpFilter(ClientIpResolver clientIpResolver) {
		FilterRegistrationBean<ClientIpFilter> registration = new FilterRegistrationBean<>(
				new ClientIpFilter(clientIpResolver));
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
		return registration;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this.interceptor).addPathPatterns("/api/public/v1/**");
	}

}
