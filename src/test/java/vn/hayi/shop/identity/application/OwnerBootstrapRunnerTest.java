package vn.hayi.shop.identity.application;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/** The bootstrap runs at startup only when both settings are present (Decision K1). */
class OwnerBootstrapRunnerTest {

	private final OwnerBootstrapService bootstrap = mock(OwnerBootstrapService.class);

	@Test
	void bootstrapsOwnerWhenEmailAndPasswordAreSet() {
		runner("owner@hayi.vn", "temporary owner passphrase").run(new DefaultApplicationArguments());

		then(this.bootstrap).should().bootstrap("owner@hayi.vn", "temporary owner passphrase");
	}

	@Test
	void doesNothingWithoutBothSettings() {
		runner("owner@hayi.vn", "").run(new DefaultApplicationArguments());
		runner(null, "temporary owner passphrase").run(new DefaultApplicationArguments());

		then(this.bootstrap).should(never()).bootstrap(anyString(), anyString());
	}

	private OwnerBootstrapRunner runner(String email, String password) {
		IdentityProperties properties = new IdentityProperties(
				new IdentityProperties.Sessions(Duration.ofHours(12), Duration.ofDays(7)),
				new IdentityProperties.Login(5, Duration.ofMinutes(15), Duration.ofMinutes(15)), Duration.ofSeconds(10),
				new IdentityProperties.Bootstrap(email, password),
				new IdentityProperties.Mfa(Duration.ofMinutes(5), 5, 5, Duration.ofMinutes(15)));
		return new OwnerBootstrapRunner(this.bootstrap, properties);
	}

}
