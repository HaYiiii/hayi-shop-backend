package vn.hayi.shop.identity.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import vn.hayi.shop.identity.application.OtpSender;
import vn.hayi.shop.identity.domain.VerificationPurpose;

/**
 * Development OTP "delivery": writes the code to the log, the only place it ever appears in plaintext (Decision AN6,
 * IMPLEMENTATION-PLAN §2.1 "OTP"). Exists only with the {@code local} and {@code test} profiles.
 */
@Component
@Profile({ "local", "test" })
class LogOtpSender implements OtpSender {

	private static final Logger log = LoggerFactory.getLogger(LogOtpSender.class);

	@Override
	public void send(String phone, String code, VerificationPurpose purpose) {
		log.warn("DEV OTP (not sent) purpose={} phone={} code={}", purpose, phone, code);
	}

}
