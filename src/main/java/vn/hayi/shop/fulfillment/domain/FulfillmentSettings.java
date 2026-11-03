package vn.hayi.shop.fulfillment.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import vn.hayi.shop.shared.error.RuleViolationsException;
import vn.hayi.shop.shared.error.RuleViolationsException.Violation;

/**
 * The editable rules of one fulfillment config (API 09 §2, Fulfillment §8.3–8.4, Decision N): horizon, slot length,
 * same-day cutoff, lead times, cancel cutoff, default slot capacity ({@code null} = no limit), pickup instructions.
 */
public record FulfillmentSettings(boolean enabled, boolean asapEnabled, int maxAdvanceDays, int slotDurationMinutes,
		int sameDayCutoffMinutes, int minLeadMinutes, Integer groupOrderMinLeadMinutes,
		Integer customerCancelCutoffMinutes, Integer defaultSlotMaxOrders, Integer defaultSlotMaxItems,
		String pickupInstructions) {

	/** ERD 08 column defaults: what a Branch shows before its first save (Decision R1). */
	public static final FulfillmentSettings DEFAULTS = new FulfillmentSettings(false, false, 7, 15, 180, 0, null, null,
			null, null, null);

	private static final Set<Integer> SLOT_DURATIONS = Set.of(15, 30);

	private static final int MAX_INSTRUCTIONS = 500;

	/**
	 * Decision R3, every violation at once, in field order.
	 * @throws RuleViolationsException {@code FULFILLMENT_CONFIG_INVALID}
	 */
	public FulfillmentSettings validated() {
		List<Violation> violations = new ArrayList<>();
		notNegative(this.maxAdvanceDays, "maxAdvanceDays", violations);
		if (!SLOT_DURATIONS.contains(this.slotDurationMinutes)) {
			violations.add(new Violation("slotDurationMinutes", "NOT_ALLOWED_VALUE"));
		}
		notNegative(this.sameDayCutoffMinutes, "sameDayCutoffMinutes", violations);
		notNegative(this.minLeadMinutes, "minLeadMinutes", violations);
		notNegative(this.groupOrderMinLeadMinutes, "groupOrderMinLeadMinutes", violations);
		notNegative(this.customerCancelCutoffMinutes, "customerCancelCutoffMinutes", violations);
		atLeastOne(this.defaultSlotMaxOrders, "defaultSlotMaxOrders", violations);
		atLeastOne(this.defaultSlotMaxItems, "defaultSlotMaxItems", violations);
		if (this.pickupInstructions != null && this.pickupInstructions.length() > MAX_INSTRUCTIONS) {
			violations.add(new Violation("pickupInstructions", "TOO_LONG"));
		}
		if (!violations.isEmpty()) {
			throw new RuleViolationsException(FulfillmentErrorCode.FULFILLMENT_CONFIG_INVALID, violations);
		}
		return this;
	}

	/** The fields whose value differs from {@code previous}, by API name: {@code [old, new]} maps for Audit. */
	public List<Map<String, Object>> diffFrom(FulfillmentSettings previous) {
		Map<String, Object> before = previous.asMap();
		Map<String, Object> after = asMap();
		Map<String, Object> oldValues = new LinkedHashMap<>();
		Map<String, Object> newValues = new LinkedHashMap<>();
		after.forEach((field, value) -> {
			if (!Objects.equals(before.get(field), value)) {
				oldValues.put(field, before.get(field));
				newValues.put(field, value);
			}
		});
		return List.of(oldValues, newValues);
	}

	private Map<String, Object> asMap() {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("isEnabled", this.enabled);
		values.put("asapEnabled", this.asapEnabled);
		values.put("maxAdvanceDays", this.maxAdvanceDays);
		values.put("slotDurationMinutes", this.slotDurationMinutes);
		values.put("sameDayCutoffMinutes", this.sameDayCutoffMinutes);
		values.put("minLeadMinutes", this.minLeadMinutes);
		values.put("groupOrderMinLeadMinutes", this.groupOrderMinLeadMinutes);
		values.put("customerCancelCutoffMinutes", this.customerCancelCutoffMinutes);
		values.put("defaultSlotMaxOrders", this.defaultSlotMaxOrders);
		values.put("defaultSlotMaxItems", this.defaultSlotMaxItems);
		values.put("pickupInstructions", this.pickupInstructions);
		return values;
	}

	private static void notNegative(Integer value, String field, List<Violation> violations) {
		if (value != null && value < 0) {
			violations.add(new Violation(field, "NEGATIVE"));
		}
	}

	private static void atLeastOne(Integer value, String field, List<Violation> violations) {
		if (value != null && value < 1) {
			violations.add(new Violation(field, "BELOW_ONE"));
		}
	}

}
