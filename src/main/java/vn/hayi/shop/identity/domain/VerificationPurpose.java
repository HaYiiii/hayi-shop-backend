package vn.hayi.shop.identity.domain;

/**
 * {@code iam_verification_challenge.purpose} (ERD 01): the MFA step of login (Decision L1) and Customer registration
 * by OTP (Decision AN1); the other purposes arrive with their tickets.
 */
public enum VerificationPurpose {

	MFA_LOGIN,
	REGISTRATION

}
