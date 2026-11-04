package vn.hayi.shop.fulfillment.domain;

/**
 * A weekly pickup window on an ISO day of week with its per-slot capacity; {@code null} capacity means the config
 * default (Decision N4).
 */
public record WeeklyWindow(int dayOfWeek, TimeWindow window, Integer maxOrders, Integer maxItems) {
}
