package vn.hayi.shop.catalog.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The fields an edit actually changed, by their API name, with old and new values — what Audit records
 * (Decision W8, like O3). Values may be {@code null}.
 */
public final class FieldDiff {

	private final Map<String, Object> oldValues = new LinkedHashMap<>();

	private final Map<String, Object> newValues = new LinkedHashMap<>();

	/** Records the field when the value changes and returns the value to keep. */
	<T> T track(String field, T current, T next) {
		if (!Objects.equals(current, next)) {
			this.oldValues.put(field, current);
			this.newValues.put(field, next);
		}
		return next;
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
