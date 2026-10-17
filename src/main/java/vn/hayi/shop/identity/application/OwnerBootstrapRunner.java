package vn.hayi.shop.identity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Runs the Owner bootstrap at startup when {@code HAYI_BOOTSTRAP_OWNER_EMAIL} and
 * {@code HAYI_BOOTSTRAP_OWNER_PASSWORD} are set (Decision K1). The password is never logged.
 */
@Component
class OwnerBootstrapRunner implements ApplicationRunner {

	private static final Logger LOG = LoggerFactory.getLogger(OwnerBootstrapRunner.class);

	private final OwnerBootstrapService bootstrap;

	private final IdentityProperties properties;

	OwnerBootstrapRunner(OwnerBootstrapService bootstrap, IdentityProperties properties) {
		this.bootstrap = bootstrap;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		IdentityProperties.Bootstrap settings = this.properties.bootstrap();
		if (!StringUtils.hasText(settings.ownerEmail()) || !StringUtils.hasText(settings.ownerPassword())) {
			return;
		}
		OwnerBootstrapService.Outcome outcome = this.bootstrap.bootstrap(settings.ownerEmail(), settings.ownerPassword());
		LOG.info("Owner bootstrap: {}", outcome);
	}

}
