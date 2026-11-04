package vn.hayi.shop.fulfillment.domain;

/** A pickup window of one date with the per-slot capacity in force there; {@code null} = no limit (Decision N4). */
public record CapacityWindow(TimeWindow window, Integer maxOrders, Integer maxItems) {
}
