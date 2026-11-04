package vn.hayi.shop.fulfillment.domain;

/** A generated slot with the capacity of the window it came from. */
public record SlotCandidate(TimeWindow slot, Integer maxOrders, Integer maxItems) {
}
