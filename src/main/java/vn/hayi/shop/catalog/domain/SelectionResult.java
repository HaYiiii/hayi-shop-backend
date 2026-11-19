package vn.hayi.shop.catalog.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One selection resolved against a Branch's offer (Catalog §5.8, Decision AJ).
 * @param normalizedOptionItemIds the known chosen items without repeats, by group then item order
 * @param unitPrice the Variant price plus the chosen items' prices, {@code null} when the Product or Variant is
 * unknown there
 * @param productAvailability {@code null} when the Product is not on the menu
 * @param variantAvailability the Variant's own state there, {@code null} when it is not offered
 * @param optionAvailability the state of each known chosen item
 */
public record SelectionResult(boolean valid, List<UUID> normalizedOptionItemIds, Long unitPrice,
		AvailabilityState productAvailability, AvailabilityState variantAvailability,
		Map<UUID, AvailabilityState> optionAvailability, List<Issue> issues) {

	/** The Product is unknown or not on the Branch's menu (AH1): nothing else is checked (AJ2). */
	public static SelectionResult notOnMenu() {
		return new SelectionResult(false, List.of(), null, null, null, Map.of(),
				List.of(new Issue(SelectionIssueCode.PRODUCT_NOT_FOUND, null, null)));
	}

	/** {@code optionItemId} or {@code optionGroupId} names what an item or group issue is about. */
	public record Issue(SelectionIssueCode code, UUID optionItemId, UUID optionGroupId) {
	}

}
