package vn.hayi.shop.fulfillment.domain;

import java.time.LocalDate;
import java.time.LocalTime;

/** A slot by its date and start, Branch local time (ERD 08 {@code uk_ful_slot_capacity__slot}). */
public record SlotKey(LocalDate date, LocalTime start) {
}
