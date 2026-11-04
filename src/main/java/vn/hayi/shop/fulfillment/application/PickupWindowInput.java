package vn.hayi.shop.fulfillment.application;

import java.time.LocalTime;

/** One pickup window of a date override. */
public record PickupWindowInput(LocalTime start, LocalTime end) {
}
