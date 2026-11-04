package vn.hayi.shop.fulfillment.domain;

import java.time.LocalDate;

/** A slot counter row with something claimed (ERD 08 {@code ful_slot_capacity}). */
public record ClaimedSlot(LocalDate date, TimeWindow slot, UsedCapacity used) {
}
