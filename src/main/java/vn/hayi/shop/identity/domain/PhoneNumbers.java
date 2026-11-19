package vn.hayi.shop.identity.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Vietnamese phone numbers as login identifiers (Identity §2.2, Decision AN2): {@code 0xxxxxxxxx}, {@code 84…} or
 * {@code +84…}, spaces, dots and dashes ignored, stored as E.164 {@code +84} + 9 digits. Other countries come later.
 */
public final class PhoneNumbers {

	private static final Pattern SEPARATORS = Pattern.compile("[\\s.\\-]");

	private static final Pattern NATIONAL = Pattern.compile("0([1-9]\\d{8})");

	private static final Pattern INTERNATIONAL = Pattern.compile("\\+?84([1-9]\\d{8})");

	private PhoneNumbers() {
	}

	/** The E.164 form, or empty when it is not a Vietnamese number. */
	public static Optional<String> normalize(String input) {
		if (input == null) {
			return Optional.empty();
		}
		String compact = SEPARATORS.matcher(input.strip()).replaceAll("");
		var national = NATIONAL.matcher(compact);
		if (national.matches()) {
			return Optional.of("+84" + national.group(1));
		}
		var international = INTERNATIONAL.matcher(compact);
		if (international.matches()) {
			return Optional.of("+84" + international.group(1));
		}
		return Optional.empty();
	}

	/** {@code +8490*****567}: what security events and logs keep of a phone. */
	public static String mask(String e164) {
		if (e164.length() < 7) {
			return "***";
		}
		return e164.substring(0, 5) + "*".repeat(e164.length() - 8) + e164.substring(e164.length() - 3);
	}

}
