package vn.hayi.shop;

import java.time.Instant;

import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application {@code Clock} (and the cache {@code Ticker}) so tests can cross TTLs and lock windows without
 * sleeping.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MutableClockConfiguration {

	@Bean
	@Primary
	MutableClock mutableClock() {
		return new MutableClock();
	}

	/** Lets cache TTLs follow the mutable clock too. */
	@Bean
	Ticker mutableClockTicker(MutableClock clock) {
		return () -> {
			Instant now = clock.instant();
			return now.getEpochSecond() * 1_000_000_000L + now.getNano();
		};
	}

}
