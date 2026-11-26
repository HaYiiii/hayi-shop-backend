package vn.hayi.shop.catalog.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The one authoritative resolver of a catalog selection at a Branch ({@code ResolveEffectiveCatalogSelection},
 * Catalog §5.8, Decision AJ): the public resolve, Cart (S4) and Create Order (S5) all use it. Prices are always the
 * server's; nothing is written. Implemented by the Catalog module.
 */
public interface CatalogSelectionResolver {

	/**
	 * @param at when the order would be fulfilled ({@code null} = now), for the selling hours (Decision AK2)
	 * @throws vn.hayi.shop.shared.error.DomainException {@code BRANCH_NOT_FOUND} unless the Branch is {@code ACTIVE}
	 */
	ResolvedSelections resolve(UUID branchId, List<SelectionRequest> lines, Instant at);

	record SelectionRequest(String ref, UUID productId, UUID variantId, List<UUID> optionItemIds, int quantity) {
	}

	/** {@code fingerprint} is an opaque marker that changes when a price, state or issue changes (AJ4). */
	record ResolvedSelections(List<ResolvedLine> lines, String fingerprint) {
	}

	/**
	 * @param unitPrice {@code null} when the Product or Variant is unknown at the Branch (AJ3)
	 * @param productAvailability {@code null} when the Product is not on the menu
	 * @param variantAvailability {@code null} when the Variant is not offered
	 * @param productName the Product's name, {@code null} when there is no such Product (for Cart, Decision AV1)
	 * @param variantName the size's name, {@code null} when the Product has no such Variant
	 * @param options the chosen items on offer, in {@code normalizedOptionItemIds} order, with their names and prices
	 */
	record ResolvedLine(String ref, boolean valid, UUID productId, UUID variantId, List<UUID> normalizedOptionItemIds,
			Long unitPrice, Long lineTotal, Availability productAvailability, Availability variantAvailability,
			Map<UUID, Availability> optionAvailability, List<Issue> issues, String productName, String variantName,
			List<ResolvedOption> options) {
	}

	record ResolvedOption(UUID optionItemId, UUID optionGroupId, String optionGroupName, String optionItemName,
			long extraPrice) {
	}

	record Issue(IssueCode code, UUID optionItemId, UUID optionGroupId) {
	}

	/** Catalog §5.8, API 02 §2. */
	enum IssueCode {

		PRODUCT_NOT_FOUND, PRODUCT_SOLD_OUT, PRODUCT_PAUSED, VARIANT_SOLD_OUT, VARIANT_NOT_AVAILABLE,
		OPTION_ITEM_SOLD_OUT, OPTION_SELECTION_INVALID, REQUIRED_OPTION_GROUP_UNSATISFIABLE, OUTSIDE_SELLING_HOURS

	}

	/** Catalog §5.6; {@code OUTSIDE_SELLING_HOURS} is derived from the selling schedule (§5.7, Decision AK). */
	enum Availability {

		NOT_AVAILABLE, AVAILABLE, SOLD_OUT, PAUSED, OUTSIDE_SELLING_HOURS

	}

}
