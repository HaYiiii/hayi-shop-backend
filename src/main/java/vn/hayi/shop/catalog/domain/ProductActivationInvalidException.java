package vn.hayi.shop.catalog.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import vn.hayi.shop.shared.error.DomainException;

/** Activation refused → {@code 422 PRODUCT_ACTIVATION_INVALID} with every missing condition in {@code issues[]}. */
public class ProductActivationInvalidException extends DomainException {

	private final List<ActivationIssue> issues;

	ProductActivationInvalidException(UUID productId, List<ActivationIssue> issues) {
		super(CatalogErrorCode.PRODUCT_ACTIVATION_INVALID, "Product " + productId + " cannot be activated: " + issues);
		this.issues = List.copyOf(issues);
	}

	public List<ActivationIssue> issues() {
		return this.issues;
	}

	@Override
	public Map<String, Object> problemProperties() {
		return Map.of("issues", this.issues.stream().map(issue -> Map.of("code", issue.name())).toList());
	}

}
