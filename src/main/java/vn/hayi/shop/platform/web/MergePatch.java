package vn.hayi.shop.platform.web;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import vn.hayi.shop.shared.patch.Change;

/**
 * Reads a JSON Merge Patch document (RFC 7396, API README §3) into {@link Change}s, telling a missing key (keep)
 * from {@code null} (clear). Collects every field violation, then {@link #validate()} throws one
 * {@link InvalidFieldsException} ({@code 400 VALIDATION_FAILED} with {@code errors[]}). Keys the endpoint did not
 * read are refused, so read-only fields such as codes cannot be slipped in.
 */
public final class MergePatch {

	private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

	private final JsonNode node;

	private final String prefix;

	private final List<InvalidFieldsException.Violation> violations;

	private final Set<String> readKeys = new LinkedHashSet<>();

	private final List<MergePatch> children = new ArrayList<>();

	private MergePatch(JsonNode node, String prefix, List<InvalidFieldsException.Violation> violations) {
		this.node = node;
		this.prefix = prefix;
		this.violations = violations;
	}

	/** {@code body} must be a JSON object. */
	public static MergePatch of(JsonNode body) {
		List<InvalidFieldsException.Violation> violations = new ArrayList<>();
		if (body == null || !body.isObject()) {
			throw new InvalidFieldsException(List.of(new InvalidFieldsException.Violation("body", "NOT_OBJECT")));
		}
		return new MergePatch(body, "", violations);
	}

	/** A text field that cannot be cleared: {@code null} or blank is refused. */
	public Change<String> requiredText(String key, int maxLength) {
		return text(key, maxLength, false, null, null);
	}

	/** A text field that cannot be cleared and must match {@code format} (violation {@code PATTERN}). */
	public Change<String> requiredText(String key, int maxLength, Pattern format) {
		return text(key, maxLength, false, format, "PATTERN");
	}

	/** A text field that {@code null} clears. */
	public Change<String> optionalText(String key, int maxLength) {
		return text(key, maxLength, true, null, null);
	}

	/** A text field that {@code null} clears and that must otherwise look like an email address. */
	public Change<String> optionalEmail(String key, int maxLength) {
		return text(key, maxLength, true, EMAIL, "EMAIL");
	}

	/** A whole number that cannot be cleared; its range is the domain's to check. */
	public Change<Long> requiredLong(String key) {
		JsonNode value = read(key);
		if (value == null) {
			return Change.keep();
		}
		if (value.isNull()) {
			reject(key, "NOT_BLANK");
			return Change.keep();
		}
		if (!value.isIntegralNumber()) {
			reject(key, "INVALID_TYPE");
			return Change.keep();
		}
		if (!value.canConvertToLong()) {
			reject(key, "OUT_OF_RANGE");
			return Change.keep();
		}
		return Change.set(value.longValue());
	}

	/** Like {@link #requiredLong} within {@code int}. */
	public Change<Integer> requiredInt(String key) {
		JsonNode value = read(key);
		if (value == null) {
			return Change.keep();
		}
		if (value.isNull()) {
			reject(key, "NOT_BLANK");
		}
		else if (!value.isIntegralNumber()) {
			reject(key, "INVALID_TYPE");
		}
		else if (!value.canConvertToInt()) {
			reject(key, "OUT_OF_RANGE");
		}
		else {
			return Change.set(value.intValue());
		}
		return Change.keep();
	}

	/** A boolean that cannot be cleared. */
	public Change<Boolean> requiredBoolean(String key) {
		JsonNode value = read(key);
		if (value == null) {
			return Change.keep();
		}
		if (!value.isBoolean()) {
			reject(key, value.isNull() ? "NOT_BLANK" : "INVALID_TYPE");
			return Change.keep();
		}
		return Change.set(value.booleanValue());
	}

	/** One of {@code allowed} (an API enum), which cannot be cleared. */
	public Change<String> requiredEnum(String key, Set<String> allowed) {
		JsonNode value = read(key);
		if (value == null) {
			return Change.keep();
		}
		if (!value.isString() || !allowed.contains(value.stringValue())) {
			reject(key, value.isNull() ? "NOT_BLANK" : "INVALID_TYPE");
			return Change.keep();
		}
		return Change.set(value.stringValue());
	}

	/** A UUID field that {@code null} clears. */
	public Change<UUID> optionalUuid(String key) {
		JsonNode value = read(key);
		if (value == null) {
			return Change.keep();
		}
		if (value.isNull()) {
			return Change.set(null);
		}
		if (value.isString()) {
			try {
				return Change.set(UUID.fromString(value.stringValue()));
			}
			catch (IllegalArgumentException ex) {
				// falls through to the violation below
			}
		}
		reject(key, "INVALID_TYPE");
		return Change.keep();
	}

	/**
	 * A nested object merged key by key; {@code null} for it is refused. {@code reader} reads its keys and returns the
	 * caller's value, or {@code null} when the key is missing.
	 */
	public <T> T mergedObject(String key, Function<MergePatch, T> reader) {
		JsonNode value = read(key);
		if (value == null) {
			return null;
		}
		if (!value.isObject()) {
			reject(key, value.isNull() ? "NOT_BLANK" : "INVALID_TYPE");
			return null;
		}
		return reader.apply(child(key, value));
	}

	/**
	 * A nested object replaced as a whole, which {@code null} clears; {@code reader} reads its (required) keys.
	 */
	public <T> Change<T> replacedObject(String key, Function<MergePatch, T> reader) {
		JsonNode value = read(key);
		if (value == null) {
			return Change.keep();
		}
		if (value.isNull()) {
			return Change.set(null);
		}
		if (!value.isObject()) {
			reject(key, "INVALID_TYPE");
			return Change.keep();
		}
		return Change.set(reader.apply(child(key, value)));
	}

	/** A required number within {@code [min, max]}, for keys of a {@link #replacedObject} only. */
	public BigDecimal requiredDecimal(String key, BigDecimal min, BigDecimal max) {
		JsonNode value = read(key);
		if (value == null || value.isNull()) {
			reject(key, "NOT_BLANK");
			return null;
		}
		if (!value.isNumber()) {
			reject(key, "INVALID_TYPE");
			return null;
		}
		BigDecimal number = value.decimalValue();
		if (number.compareTo(min) < 0 || number.compareTo(max) > 0) {
			reject(key, "OUT_OF_RANGE");
			return null;
		}
		return number;
	}

	/** @throws InvalidFieldsException for every violation and every key that was not read */
	public void validate() {
		rejectUnreadKeys();
		if (!this.violations.isEmpty()) {
			throw new InvalidFieldsException(List.copyOf(this.violations));
		}
	}

	private Change<String> text(String key, int maxLength, boolean nullable, Pattern format, String formatCode) {
		JsonNode value = read(key);
		if (value == null) {
			return Change.keep();
		}
		if (value.isNull()) {
			if (nullable) {
				return Change.set(null);
			}
			reject(key, "NOT_BLANK");
			return Change.keep();
		}
		if (!value.isString()) {
			reject(key, "INVALID_TYPE");
			return Change.keep();
		}
		String text = value.stringValue();
		if (!nullable && text.isBlank()) {
			reject(key, "NOT_BLANK");
		}
		else if (text.length() > maxLength) {
			reject(key, "SIZE");
		}
		else if (format != null && !format.matcher(text).matches()) {
			reject(key, formatCode);
		}
		else {
			return Change.set(text);
		}
		return Change.keep();
	}

	private JsonNode read(String key) {
		this.readKeys.add(key);
		return this.node.get(key);
	}

	private MergePatch child(String key, JsonNode value) {
		MergePatch child = new MergePatch(value, path(key) + ".", this.violations);
		this.children.add(child);
		return child;
	}

	private void rejectUnreadKeys() {
		for (String key : this.node.propertyNames()) {
			if (!this.readKeys.contains(key)) {
				reject(key, "NOT_ALLOWED");
			}
		}
		this.children.forEach(MergePatch::rejectUnreadKeys);
	}

	private void reject(String key, String code) {
		this.violations.add(new InvalidFieldsException.Violation(path(key), code));
	}

	private String path(String key) {
		return this.prefix + key;
	}

}
