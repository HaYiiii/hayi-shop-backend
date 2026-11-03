package vn.hayi.shop.fulfillment.application;

/** One fulfillment config as the Backoffice reads it; {@code version} 0 means not saved yet (Decision R1). */
public record FulfillmentConfigView(String fulfillmentType, FulfillmentRules rules, long version) {
}
