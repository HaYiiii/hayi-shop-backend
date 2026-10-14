package vn.hayi.shop.platform.persistence;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single source of "now" (UTC). Inject {@link Clock} instead of calling {@code Instant.now()} so tests can fix time.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
