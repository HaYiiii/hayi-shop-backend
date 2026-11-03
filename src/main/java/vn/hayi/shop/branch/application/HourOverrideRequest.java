package vn.hayi.shop.branch.application;

import java.time.LocalDate;
import java.util.List;

import vn.hayi.shop.branch.api.OpeningInterval;

/** The hours of one date as entered (API 09 §1.4): closed all day, or its own intervals. */
public record HourOverrideRequest(LocalDate date, boolean isClosed, List<OpeningInterval> intervals, String reason) {

	public HourOverrideRequest {
		intervals = List.copyOf(intervals);
	}

}
