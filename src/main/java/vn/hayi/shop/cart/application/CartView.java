package vn.hayi.shop.cart.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import vn.hayi.shop.catalog.api.CatalogSelectionResolver;

/**
 * A Cart as the Customer sees it (API 03 §1, Decision AV1): every item priced and checked again now; nothing here is
 * a final price.
 * @param cartId {@code null} while there is no {@code ACTIVE} Cart
 * @param updatedAt {@code null} while there is no {@code ACTIVE} Cart
 */
public record CartView(UUID cartId, UUID branchId, long version, List<Item> items, String couponCode,
		long provisionalSubtotal, boolean hasBlockingIssues, Instant updatedAt) {

	static CartView empty(UUID branchId) {
		return new CartView(null, branchId, 0, List.of(), null, 0, false, null);
	}

	/**
	 * @param unitPrice the current price, {@code null} when the Product or Variant is unknown at the Branch
	 * @param productName {@code null} when the Product is gone
	 */
	public record Item(UUID cartItemId, UUID productId, String productName, UUID variantId, String variantName,
			List<Option> options, String note, int quantity, Long unitPrice, Long lineTotal, List<ItemIssue> issues) {
	}

	/** Names and price are {@code null} when the item is no longer on offer for this Product. */
	public record Option(UUID optionItemId, String optionGroupName, String optionItemName, Long extraPrice) {
	}

	/** Every resolver issue blocks checkout (API 03 §2, Decision F). */
	public record ItemIssue(String code, UUID optionItemId, UUID optionGroupId) {

		public static final String SEVERITY = "BLOCKING";

		static ItemIssue of(CatalogSelectionResolver.Issue issue) {
			return new ItemIssue(issue.code().name(), issue.optionItemId(), issue.optionGroupId());
		}

		Map<String, Object> asProblemEntry() {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("code", this.code);
			entry.put("severity", SEVERITY);
			if (this.optionItemId != null) {
				entry.put("optionItemId", this.optionItemId);
			}
			if (this.optionGroupId != null) {
				entry.put("optionGroupId", this.optionGroupId);
			}
			return entry;
		}

	}

	/** One {@code ACTIVE} Cart for the badge (Decision AV3). */
	public record Summary(UUID cartId, UUID branchId, int itemCount, Instant updatedAt) {
	}

}
