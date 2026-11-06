package vn.hayi.shop.catalog.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import vn.hayi.shop.shared.error.DomainException;

/** Activation refused → {@code 422 PRODUCT_ACTIVATION_INVALID} with every missing condition in {@code issues[]}. */
public class ProductActivationInvalidException extends DomainException {

	private final List<Issue> issues;

	ProductActivationInvalidException(UUID productId, List<Issue> issues) {
		super(CatalogErrorCode.PRODUCT_ACTIVATION_INVALID, "Product " + productId + " cannot be activated: " + issues);
		this.issues = List.copyOf(issues);
	}

	public List<Issue> issues() {
		return this.issues;
	}

	@Override
	public Map<String, Object> problemProperties() {
		return Map.of("issues", this.issues.stream().map(Issue::asProblemEntry).toList());
	}

	/** {@code optionGroupId} names the group for {@code OPTION_CONFIG_INVALID} (Decision AC4), else {@code null}. */
	public record Issue(ActivationIssue code, UUID optionGroupId) {

		static Issue of(ActivationIssue code) {
			return new Issue(code, null);
		}

		Map<String, Object> asProblemEntry() {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("code", this.code.name());
			if (this.optionGroupId != null) {
				entry.put("optionGroupId", this.optionGroupId);
			}
			return entry;
		}

	}

}
