package vn.hayi.shop.branch.application;

import java.time.LocalDate;
import java.util.List;

import vn.hayi.shop.branch.api.OpeningInterval;

/** A Branch's weekly hours and overrides from today on, with the Branch version for {@code ETag} (Decision Q4). */
public record BranchHoursView(long version, List<WeeklyIntervalRequest> weekly, List<Override> overrides) {

	/** The hours of one date. */
	public record Override(LocalDate date, boolean isClosed, List<OpeningInterval> intervals, String reason) {
	}

}
