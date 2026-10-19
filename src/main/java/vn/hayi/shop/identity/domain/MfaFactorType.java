package vn.hayi.shop.identity.domain;

/** MFA v1 is TOTP only; recovery codes are separate (Identity §2.4, Decision D4). */
public enum MfaFactorType {

	TOTP

}
