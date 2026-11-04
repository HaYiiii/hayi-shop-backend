package vn.hayi.shop.branch.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

	private static final Logger LOG = LoggerFactory.getLogger(PublicBranchService.class);

	private static final int OVERRIDE_DAYS = 7;

	private final BranchRepository branches;

	private final BranchFulfillmentSummary fulfillment;

	private final Clock clock;

	private final TransactionTemplate readOnly;

	PublicBranchService(BranchRepository branches, BranchFulfillmentSummary fulfillment, Clock clock,
			PlatformTransactionManager transactions) {
		this.branches = branches;
		this.fulfillment = fulfillment;
		this.clock = clock;
		this.readOnly = new TransactionTemplate(transactions);
		this.readOnly.setReadOnly(true);
	}

	/** Every {@code ACTIVE} Branch, by code (U1). */
	public List<PublicBranchView> listActive() {
		LocalDateTime now = now();
		List<PublicBranchView> views = this.readOnly.execute(status -> this.branches
			.findByStatusOrderByBranchCode(BranchStatus.ACTIVE).stream()
			.map(branch -> view(branch, now, false))
			.toList());
		return views.stream().map(this::withOrderingFacts).toList();
	}

	/** @throws DomainException {@code BRANCH_NOT_FOUND} when unknown or not {@code ACTIVE} (U1) */
	public PublicBranchView getActive(UUID branchId) {
		LocalDateTime now = now();
		PublicBranchView view = this.readOnly.execute(status -> this.branches.findById(branchId)
			.filter(found -> found.getStatus() == BranchStatus.ACTIVE)
			.map(branch -> view(branch, now, true))
			.orElseThrow(() -> new DomainException(BranchErrorCode.BRANCH_NOT_FOUND, "No ACTIVE Branch " + branchId)));
		return withOrderingFacts(view);
	}

	/**
	 * Asked outside the Branch read so each call runs in its own transaction: a Fulfillment failure cannot mark a
	 * shared transaction rollback-only and fail the whole answer at commit.
	 */
	private PublicBranchView withOrderingFacts(PublicBranchView view) {
		UUID id = view.branch().branchId();
		return new PublicBranchView(view.branch(), view.openNow(), view.todayHours(),
				orElse(id, "fulfillmentTypes", () -> this.fulfillment.fulfillmentTypes(id), List.of()),
				orElse(id, "earliestPickupAt", () -> this.fulfillment.earliestPickupAt(id).orElse(null), null),
				view.weeklyHours(), view.overrides());
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
				todayHours.stream().map(PublicBranchService::interval).toList(), List.of(), null, weekly, overrides);
	}

	/**
	 * Ordering facts are a hint: if Fulfillment fails for one Branch, that Branch shows none rather than the whole
	 * public list failing (review of PR #54). The failure is logged for the server, never shown.
	 */
	private static <T> T orElse(UUID branchId, String what, Supplier<T> fact, T fallback) {
		try {
			return fact.get();
		}
		catch (RuntimeException ex) {
			LOG.warn("Public Branch {}: {} unavailable", branchId, what, ex);
			return fallback;
		}
	}

	private LocalDateTime now() {
		return LocalDateTime.ofInstant(this.clock.instant(), BranchTime.DEFAULT_ZONE);
	}

	private static OpeningInterval interval(TimeRange range) {
		return new OpeningInterval(range.open(), range.close());
	}

}
