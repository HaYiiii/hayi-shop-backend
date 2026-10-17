package vn.hayi.shop.identity.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import vn.hayi.shop.shared.error.DomainException;

/**
 * Password policy for internal users (Identity §2.4, Decision K4): 12–128 characters, not a common password, no
 * composition rules. "Different from the current password" needs the hash, so the caller checks it.
 */
public final class PasswordPolicy {

	public static final int MIN_LENGTH = 12;

	public static final int MAX_LENGTH = 128;

	/** Common passwords of policy length, lowercased; loaded once from the classpath. */
	private static final Set<String> COMMON = loadCommonPasswords();

	private PasswordPolicy() {
	}

	public static void check(String password) {
		int length = password.codePointCount(0, password.length());
		if (length < MIN_LENGTH || length > MAX_LENGTH || COMMON.contains(password.toLowerCase(Locale.ROOT))) {
			throw violation();
		}
	}

	private static Set<String> loadCommonPasswords() {
		try (InputStream in = PasswordPolicy.class.getResourceAsStream("/identity/common-passwords.txt")) {
			if (in == null) {
				throw new IllegalStateException("identity/common-passwords.txt missing from the classpath");
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				return reader.lines()
					.filter(line -> !line.isBlank() && !line.startsWith("#"))
					.collect(Collectors.toUnmodifiableSet());
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public static DomainException violation() {
		return new DomainException(IdentityErrorCode.AUTH_PASSWORD_POLICY_VIOLATION, "Password violates policy");
	}

}
