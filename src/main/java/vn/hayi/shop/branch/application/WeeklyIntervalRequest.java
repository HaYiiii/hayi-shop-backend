package vn.hayi.shop.branch.application;

import java.time.LocalTime;

/** One weekly opening range as entered (API 09 §1.4); {@code dayOfWeek} 1 = Monday … 7 = Sunday. */
public record WeeklyIntervalRequest(int dayOfWeek, LocalTime open, LocalTime close) {
}
