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
	 * @param at when the order would be fulfilled ({@code null} = now); selling hours use it from BE #70
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
	 */
	record ResolvedLine(String ref, boolean valid, UUID productId, UUID variantId, List<UUID> normalizedOptionItemIds,
			Long unitPrice, Long lineTotal, Availability productAvailability, Availability variantAvailability,
			Map<UUID, Availability> optionAvailability, List<Issue> issues) {
	}

	record Issue(IssueCode code, UUID optionItemId, UUID optionGroupId) {
	}

	/** Catalog §5.8, API 02 §2 ({@code OUTSIDE_SELLING_HOURS} from BE #70). */
	enum IssueCode {

		PRODUCT_NOT_FOUND, PRODUCT_SOLD_OUT, PRODUCT_PAUSED, VARIANT_SOLD_OUT, VARIANT_NOT_AVAILABLE,
		OPTION_ITEM_SOLD_OUT, OPTION_SELECTION_INVALID, REQUIRED_OPTION_GROUP_UNSATISFIABLE

	}

	/** Catalog §5.6. */
	enum Availability {

		NOT_AVAILABLE, AVAILABLE, SOLD_OUT, PAUSED

	}

}
