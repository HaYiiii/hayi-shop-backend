package vn.hayi.shop.identity.application;

import vn.hayi.shop.identity.domain.VerificationPurpose;

/**
 * Delivers a one-time code (Identity §2.3). The only implementation so far logs the code and exists only with the
 * {@code local} / {@code test} profiles (Decision AN6): without a real SMS provider the application does not start.
 */
public interface OtpSender {

	/** @param phone E.164 */
	void send(String phone, String code, VerificationPurpose purpose);

}
