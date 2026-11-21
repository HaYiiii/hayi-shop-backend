package vn.hayi.shop.identity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import vn.hayi.shop.identity.application.OtpSender;
import vn.hayi.shop.identity.domain.VerificationPurpose;

/** Keeps the last OTP sent to each phone, so tests can answer it (stands in for the SMS, Decision AN6). */
public class CapturingOtpSender implements OtpSender {

	private final Map<String, String> lastCodes = new ConcurrentHashMap<>();

	@Override
	public void send(String phone, String code, VerificationPurpose purpose) {
		this.lastCodes.put(phone, code);
	}

	/** @param phone E.164 */
	public String lastCode(String phone) {
		return this.lastCodes.get(phone);
	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class Config {

		@Bean
		@Primary
		CapturingOtpSender capturingOtpSender() {
			return new CapturingOtpSender();
		}

	}

}
