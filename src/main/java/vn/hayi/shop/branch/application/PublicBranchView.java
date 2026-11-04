package vn.hayi.shop.branch.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import vn.hayi.shop.branch.api.OpeningInterval;

/**
 * An {@code ACTIVE} Branch as customers see it (API 02 §1, Decision U): no email, no internal override reason.
 * {@code weeklyHours} and {@code overrides} are filled for the detail only (empty in the list).
 */
public record PublicBranchView(BranchView branch, boolean openNow, List<OpeningInterval> todayHours,
		List<String> fulfillmentTypes, Instant earliestPickupAt, List<WeeklyIntervalRequest> weeklyHours,
		List<Override> overrides) {

	/** One date's hours of the next seven days, without its reason (U3). */
	public record Override(LocalDate date, boolean isClosed, List<OpeningInterval> intervals) {
	}

}
