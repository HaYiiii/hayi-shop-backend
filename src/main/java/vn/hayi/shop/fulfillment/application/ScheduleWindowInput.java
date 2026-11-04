package vn.hayi.shop.fulfillment.application;

import java.time.LocalTime;

/** A weekly pickup window as entered and read (API 09 §2.2); {@code null} capacity = config default. */
public record ScheduleWindowInput(int dayOfWeek, LocalTime start, LocalTime end, Integer maxOrders, Integer maxItems) {
}
