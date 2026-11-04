package vn.hayi.shop.branch.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.branch.api.BranchFulfillmentSummary;
import vn.hayi.shop.branch.api.OpeningInterval;
import vn.hayi.shop.branch.domain.Branch;
import vn.hayi.shop.branch.domain.BranchErrorCode;
import vn.hayi.shop.branch.domain.BranchRepository;
import vn.hayi.shop.branch.domain.BranchStatus;
import vn.hayi.shop.branch.domain.TimeRange;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.time.BranchTime;

/**
 * The public, customer-facing view of {@code ACTIVE} Branches (API 02 §1, Decision N5, U). Ordering facts come from
 * Fulfillment through Branch's own {@link BranchFulfillmentSummary} port.
 */
@Service
public class PublicBranchService {

	private static final int OVERRIDE_DAYS = 7;

	private final BranchRepository branches;

	private final BranchFulfillmentSummary fulfillment;

	private final Clock clock;

	PublicBranchService(BranchRepository branches, BranchFulfillmentSummary fulfillment, Clock clock) {
		this.branches = branches;
		this.fulfillment = fulfillment;
		this.clock = clock;
	}

	/** Every {@code ACTIVE} Branch, by code (U1). */
	@Transactional(readOnly = true)
	public List<PublicBranchView> listActive() {
		LocalDateTime now = now();
		return this.branches.findByStatusOrderByBranchCode(BranchStatus.ACTIVE).stream()
			.map(branch -> view(branch, now, false))
			.toList();
	}

	/** @throws DomainException {@code BRANCH_NOT_FOUND} when unknown or not {@code ACTIVE} (U1) */
	@Transactional(readOnly = true)
	public PublicBranchView getActive(UUID branchId) {
		Branch branch = this.branches.findById(branchId)
			.filter(found -> found.getStatus() == BranchStatus.ACTIVE)
			.orElseThrow(() -> new DomainException(BranchErrorCode.BRANCH_NOT_FOUND, "No ACTIVE Branch " + branchId));
		return view(branch, now(), true);
	}

	private PublicBranchView view(Branch branch, LocalDateTime now, boolean detail) {
		LocalDate today = now.toLocalDate();
		List<TimeRange> todayHours = branch.openingHoursOn(today);
		LocalTime time = now.toLocalTime();
		boolean openNow = todayHours.stream()
			.anyMatch(range -> !time.isBefore(range.open()) && time.isBefore(range.close()));
		List<WeeklyIntervalRequest> weekly = detail ? branch.getWeeklyHours().stream()
			.map(hours -> new WeeklyIntervalRequest(hours.dayOfWeek(), hours.range().open(), hours.range().close()))
			.toList() : List.of();
		List<PublicBranchView.Override> overrides = detail ? branch.getOverridesFrom(today).stream()
			.filter(override -> override.date().isBefore(today.plusDays(OVERRIDE_DAYS)))
			.map(override -> new PublicBranchView.Override(override.date(), override.closed(),
					override.ranges().stream().map(PublicBranchService::interval).toList()))
			.toList() : List.of();
		return new PublicBranchView(BranchView.of(branch), openNow,
				todayHours.stream().map(PublicBranchService::interval).toList(),
				this.fulfillment.fulfillmentTypes(branch.getId()),
				this.fulfillment.earliestPickupAt(branch.getId()).orElse(null), weekly, overrides);
	}

	private LocalDateTime now() {
		return LocalDateTime.ofInstant(this.clock.instant(), BranchTime.DEFAULT_ZONE);
	}

	private static OpeningInterval interval(TimeRange range) {
		return new OpeningInterval(range.open(), range.close());
	}

}
