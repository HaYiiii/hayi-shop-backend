package vn.hayi.shop.identity.domain;

/** {@code iam_verification_challenge.channel} (ERD 01): TOTP for MFA, SMS for OTP (Decision AN1). */
public enum VerificationChannel {

	TOTP,
	SMS

}
