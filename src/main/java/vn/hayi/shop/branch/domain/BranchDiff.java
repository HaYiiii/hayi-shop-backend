package vn.hayi.shop.branch.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fields an edit actually changed, by their API name ({@code name}, {@code address.ward}, {@code location}…),
 * with old and new values — what Audit records (Decision O3). Values may be {@code null}.
 */
public final class BranchDiff {

	private final Map<String, Object> oldValues = new LinkedHashMap<>();

	private final Map<String, Object> newValues = new LinkedHashMap<>();

	void put(String field, Object oldValue, Object newValue) {
		this.oldValues.put(field, oldValue);
		this.newValues.put(field, newValue);
	}

	public boolean isEmpty() {
		return this.oldValues.isEmpty();
	}

	public Map<String, Object> oldValues() {
		return Collections.unmodifiableMap(this.oldValues);
	}

	public Map<String, Object> newValues() {
		return Collections.unmodifiableMap(this.newValues);
	}

}
