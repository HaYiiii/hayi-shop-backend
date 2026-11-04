package vn.hayi.shop.branch.api;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A Branch's actual opening hours (Branch §4.4, Decision Q5), for modules such as Fulfillment whose schedules must sit
 * inside them. Implemented by the Branch module.
 */
public interface BranchOpeningHours {

	/**
	 * The ranges the Branch is open on {@code date} (its local calendar date, {@code Asia/Ho_Chi_Minh}), sorted by
	 * opening time: the date's override if any, otherwise the weekly schedule. Empty means closed, also for an unknown
	 * Branch.
	 */
	List<OpeningInterval> on(UUID branchId, LocalDate date);

	/**
	 * The weekly schedule of {@code day}, ignoring date overrides, sorted by opening time; for checking other weekly
	 * schedules against it (Decision S5). Empty means closed that day, also for an unknown Branch.
	 */
	List<OpeningInterval> weekly(UUID branchId, DayOfWeek day);

}
