package vn.hayi.shop.shared.error;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Values that are well-formed but break business rules, reported per field → the code's status (usually {@code 422})
 * with {@code errors[] { field, code }} (API README §6).
 */
public class RuleViolationsException extends DomainException {

	private final List<Violation> violations;

	public RuleViolationsException(ErrorCode code, List<Violation> violations) {
		super(code, code.name() + ": " + violations);
		this.violations = List.copyOf(violations);
	}

	public List<Violation> violations() {
		return this.violations;
	}

	@Override
	public Map<String, Object> problemProperties() {
		return Map.of("errors", this.violations.stream().map(Violation::asProblemEntry).toList());
	}

	/** {@code field} is the request path ({@code slotDurationMinutes}, {@code intervals[0]}). */
	public record Violation(String field, String code) {

		Map<String, Object> asProblemEntry() {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("field", this.field);
			entry.put("code", this.code);
			return entry;
		}

	}

}
