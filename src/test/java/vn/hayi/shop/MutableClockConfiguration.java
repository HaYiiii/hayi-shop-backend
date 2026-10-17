package vn.hayi.shop;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the application {@code Clock} so tests can cross TTLs and lock windows without sleeping. */
@TestConfiguration(proxyBeanMethods = false)
public class MutableClockConfiguration {

	@Bean
	@Primary
	MutableClock mutableClock() {
		return new MutableClock();
	}

}
