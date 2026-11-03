package vn.hayi.shop.branch.domain;

/** One opening range on an ISO day of week (1 = Monday … 7 = Sunday). */
public record WeeklyHours(int dayOfWeek, TimeRange range) {
}
