package vn.hayi.shop.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** A login lock policy that would relock after a single failure stops the application from starting. */
class IdentityPropertiesTest {

	private final ApplicationContextRunner context = new ApplicationContextRunner()
		.withUserConfiguration(Properties.class);

	@Test
	void defaultsBind() {
		this.context.run(started -> assertThat(started).hasNotFailed());
	}

	@Test
	void lockShorterThanTheFailureWindowFailsStartup() {
		this.context.withPropertyValues("hayi.identity.login.lock-duration=5m")
			.run(started -> assertThat(started).hasFailed());
	}

	@EnableConfigurationProperties(IdentityProperties.class)
	static class Properties {

	}

}
