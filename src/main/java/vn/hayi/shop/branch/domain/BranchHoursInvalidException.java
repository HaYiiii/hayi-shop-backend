package vn.hayi.shop.branch.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import vn.hayi.shop.shared.error.DomainException;

/** Business hours that break Decision Q2 → {@code 422 BRANCH_HOURS_INVALID} with one {@code errors[]} per problem. */
public class BranchHoursInvalidException extends DomainException {

	private final List<Violation> violations;

	BranchHoursInvalidException(List<Violation> violations) {
		super(BranchErrorCode.BRANCH_HOURS_INVALID, "Invalid business hours: " + violations);
		this.violations = List.copyOf(violations);
	}

	public List<Violation> violations() {
		return this.violations;
	}

	@Override
	public Map<String, Object> problemProperties() {
		return Map.of("errors", this.violations.stream().map(Violation::asProblemEntry).toList());
	}

	/** {@code field} is the request path ({@code weekly[2]}, {@code intervals[0]}, {@code date}). */
	public record Violation(String field, String code) {

		Map<String, Object> asProblemEntry() {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("field", this.field);
			entry.put("code", this.code);
			return entry;
		}

	}

}
