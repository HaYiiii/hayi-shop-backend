package vn.hayi.shop.identity.domain;

import java.util.Locale;
import java.util.Set;

import vn.hayi.shop.shared.error.DomainException;

/**
 * Password policy for internal users (Identity §2.4, Decision K4): 12–128 characters, not a common password, no
 * composition rules. "Different from the current password" needs the hash, so the caller checks it.
 */
public final class PasswordPolicy {

	public static final int MIN_LENGTH = 12;

	public static final int MAX_LENGTH = 128;

	/** Common passwords of policy length; compared case-insensitively. */
	private static final Set<String> COMMON = Set.of("123456789012", "1234567890123", "12345678901234",
			"123456123456", "111111111111", "000000000000", "123123123123", "qwertyuiopas", "qwerty123456",
			"qwertyuiop12", "qwertyuiop123", "1q2w3e4r5t6y", "1qaz2wsx3edc", "zaq12wsxcde3", "password1234",
			"password12345", "password123456", "password@123", "password!123", "passwordpassword", "iloveyou1234",
			"abcdefghijkl", "abcd12345678", "abc123456789", "admin1234567", "administrator", "welcome12345",
			"letmein12345", "changeme1234", "football1234", "baseball1234", "sunshine1234", "princess1234",
			"superman1234", "trustno11234", "matkhau12345", "matkhau123456", "anhyeuem1234", "hayishop1234",
			"hayishop2026", "hayi12345678", "owner1234567");

	private PasswordPolicy() {
	}

	public static void check(String password) {
		int length = password.codePointCount(0, password.length());
		if (length < MIN_LENGTH || length > MAX_LENGTH || COMMON.contains(password.toLowerCase(Locale.ROOT))) {
			throw violation();
		}
	}

	public static DomainException violation() {
		return new DomainException(IdentityErrorCode.AUTH_PASSWORD_POLICY_VIOLATION, "Password violates policy");
	}

}
