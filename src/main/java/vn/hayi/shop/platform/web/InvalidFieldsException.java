package vn.hayi.shop.platform.web;

import java.util.List;

/**
 * A request body the web layer validated by hand (e.g. a {@link MergePatch}) has invalid fields → {@code 400
 * VALIDATION_FAILED} with one {@code errors[]} entry per violation (API README §6); the message comes from
 * {@code validation.<CODE>}.
 */
public class InvalidFieldsException extends RuntimeException {

	private final List<Violation> violations;

	public InvalidFieldsException(List<Violation> violations) {
		super("Invalid fields: " + violations);
		this.violations = List.copyOf(violations);
	}

	public List<Violation> violations() {
		return this.violations;
	}

	/** {@code field} is the dotted JSON path, {@code code} an {@code UPPER_SNAKE_CASE} reason. */
	public record Violation(String field, String code) {
	}

}
