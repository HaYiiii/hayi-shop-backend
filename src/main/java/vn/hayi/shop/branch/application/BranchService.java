package vn.hayi.shop.branch.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.audit.api.AuditEntry;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.audit.api.AuditSource;
import vn.hayi.shop.branch.api.BranchCommitment;
import vn.hayi.shop.branch.api.BranchCommitmentContributor;
import vn.hayi.shop.branch.api.BranchCommitmentType;
import vn.hayi.shop.branch.domain.Branch;
import vn.hayi.shop.branch.domain.BranchAddress;
import vn.hayi.shop.branch.domain.BranchDiff;
import vn.hayi.shop.branch.domain.BranchEdit;
import vn.hayi.shop.branch.domain.BranchErrorCode;
import vn.hayi.shop.branch.domain.BranchRepository;
import vn.hayi.shop.branch.domain.BranchStatus;
import vn.hayi.shop.branch.domain.GeoPoint;
import vn.hayi.shop.platform.security.PermissionScope;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.error.DomainException;

/**
 * Creates, edits, moves through the lifecycle and reads Branches (Branch §4.2–4.5, API 09 §1); every change is
 * audited in the same transaction.
 */
@Service
public class BranchService {

	private static final String CODE_UNIQUE_KEY = "uk_brn_branch__branch_code";

	private final BranchRepository branches;

	private final AuditRecorder audit;

	private final Clock clock;

	private final ObjectProvider<BranchCommitmentContributor> contributors;

	BranchService(BranchRepository branches, AuditRecorder audit, Clock clock,
			ObjectProvider<BranchCommitmentContributor> contributors) {
		this.branches = branches;
		this.audit = audit;
		this.clock = clock;
		this.contributors = contributors;
	}

	@Transactional
	public BranchView create(NewBranch request, ActorContext actor, String ipAddress, String userAgent) {
		String code = Branch.normalizeCode(request.branchCode());
		if (this.branches.existsByBranchCode(code)) {
			throw codeTaken(code);
		}
		Branch branch;
		try {
			branch = this.branches.saveAndFlush(Branch.create(code, request.name(),
					new BranchAddress(request.addressLine(), request.ward(), request.district(), request.province()),
					(request.lat() != null) ? new GeoPoint(request.lat(), request.lng()) : null, request.phone(),
					request.email(), this.clock.instant()));
		}
		catch (DataIntegrityViolationException ex) {
			// A concurrent create took the code after the check above; any other violation is a bug, not a conflict.
			if (isCodeTaken(ex)) {
				throw codeTaken(code);
			}
			throw ex;
		}
		this.audit.record(AuditEntry.of(actor, AuditSource.BACKOFFICE_WEB, "BRANCH_CREATE", "BRANCH", branch.getId())
			.withBranch(branch.getId())
			.withNewValue(snapshot(branch))
			.withClient(ipAddress, userAgent));
		return BranchView.of(branch);
	}

	/**
	 * Edits a Branch's information if it is still at {@code expected} version (API README §8); a lost race with a
	 * concurrent edit fails the optimistic lock on flush, which the web layer reports as {@code VERSION_CONFLICT}.
	 * Changes are audited in the same transaction with old/new values of the changed fields only (Decision O3).
	 */
	@Transactional
	public BranchView update(UUID branchId, BranchChanges changes, ExpectedVersion expected, ActorContext actor,
			String ipAddress, String userAgent) {
		Branch branch = find(branchId);
		expected.verify(branch.getVersion());
		BranchDiff diff = branch.edit(new BranchEdit(changes.name(), changes.addressLine(), changes.ward(),
				changes.district(), changes.province(),
				changes.location().map(location -> new GeoPoint(location.lat(), location.lng())), changes.phone(),
				changes.email()), this.clock.instant());
		if (diff.isEmpty()) {
			return BranchView.of(branch);
		}
		this.branches.saveAndFlush(branch);
		this.audit.record(AuditEntry.of(actor, AuditSource.BACKOFFICE_WEB, "BRANCH_UPDATE", "BRANCH", branch.getId())
			.withBranch(branch.getId())
			.withOldValue(diff.oldValues())
			.withNewValue(diff.newValues())
			.withClient(ipAddress, userAgent));
		return BranchView.of(branch);
	}

	/** {@code DRAFT/INACTIVE → ACTIVE} (Branch §4.3, Decision P1–P2). */
	@Transactional
	public BranchView activate(UUID branchId, ExpectedVersion expected, ActorContext actor, String ipAddress,
			String userAgent) {
		return transition(branchId, expected, "BRANCH_ACTIVATE", false, Branch::activate, actor, ipAddress,
				userAgent);
	}

	/** {@code ACTIVE → INACTIVE}, refused while commitments are open (Branch §4.5, Decision P3). */
	@Transactional
	public BranchView deactivate(UUID branchId, ExpectedVersion expected, ActorContext actor, String ipAddress,
			String userAgent) {
		return transition(branchId, expected, "BRANCH_DEACTIVATE", true, Branch::deactivate, actor, ipAddress,
				userAgent);
	}

	/** {@code → CLOSED} for good, refused while commitments are open; the caller has checked step-up (P4). */
	@Transactional
	public BranchView close(UUID branchId, ExpectedVersion expected, ActorContext actor, String ipAddress,
			String userAgent) {
		return transition(branchId, expected, "BRANCH_CLOSE", true, Branch::close, actor, ipAddress, userAgent);
	}

	/**
	 * What blocks deactivate/close now: every contributor's open commitments, summed per type, zero counts dropped,
	 * in {@link BranchCommitmentType} order (Decision P3).
	 */
	@Transactional(readOnly = true)
	public List<BranchCommitment> closureCheck(UUID branchId) {
		return openCommitments(find(branchId).getId());
	}

	/**
	 * {@code expected} is {@code null} when the caller sent no {@code If-Match} (optional on commands, API README §8).
	 * The transition is applied first, so an invalid one is refused before any commitment is counted; a refusal for
	 * open commitments rolls it back.
	 */
	private BranchView transition(UUID branchId, ExpectedVersion expected, String action, boolean needsNoCommitments,
			BiFunction<Branch, Instant, BranchStatus> move, ActorContext actor, String ipAddress, String userAgent) {
		Branch branch = find(branchId);
		if (expected != null) {
			expected.verify(branch.getVersion());
		}
		BranchStatus previous = move.apply(branch, this.clock.instant());
		if (needsNoCommitments) {
			List<BranchCommitment> conflicts = openCommitments(branchId);
			if (!conflicts.isEmpty()) {
				throw new BranchOpenCommitmentsException(branchId, conflicts);
			}
		}
		this.branches.saveAndFlush(branch);
		this.audit.record(AuditEntry.of(actor, AuditSource.BACKOFFICE_WEB, action, "BRANCH", branch.getId())
			.withBranch(branch.getId())
			.withOldValue(Map.of("status", previous.name()))
			.withNewValue(Map.of("status", branch.getStatus().name()))
			.withClient(ipAddress, userAgent));
		return BranchView.of(branch);
	}

	private List<BranchCommitment> openCommitments(UUID branchId) {
		Map<BranchCommitmentType, Long> totals = new EnumMap<>(BranchCommitmentType.class);
		this.contributors.orderedStream()
			.flatMap(contributor -> contributor.openCommitments(branchId).stream())
			.forEach(commitment -> totals.merge(commitment.type(), commitment.count(), Long::sum));
		return totals.entrySet().stream()
			.filter(total -> total.getValue() > 0)
			.map(total -> new BranchCommitment(total.getKey(), total.getValue()))
			.toList();
	}

	@Transactional(readOnly = true)
	public BranchView get(UUID branchId) {
		return BranchView.of(find(branchId));
	}

	private Branch find(UUID branchId) {
		return this.branches.findById(branchId)
			.orElseThrow(() -> new DomainException(BranchErrorCode.BRANCH_NOT_FOUND, "No Branch " + branchId));
	}

	/**
	 * The Branches in {@code scope}, sorted by code; {@code statuses} empty means every status. A small fixed list,
	 * not paged (API 09 §1.1).
	 */
	@Transactional(readOnly = true)
	public List<BranchView> list(PermissionScope scope, Set<String> statuses) {
		List<Branch> visible = scope.everyBranch() ? this.branches.findAllByOrderByBranchCode()
				: this.branches.findByIdInOrderByBranchCode(scope.branchIds());
		return visible.stream()
			.filter(branch -> statuses.isEmpty() || statuses.contains(branch.getStatus().name()))
			.map(BranchView::of)
			.toList();
	}

	private static Map<String, Object> snapshot(Branch branch) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("branchCode", branch.getBranchCode());
		values.put("name", branch.getName());
		values.put("status", branch.getStatus().name());
		return values;
	}

	private static boolean isCodeTaken(DataIntegrityViolationException ex) {
		String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
		return message != null && message.contains(CODE_UNIQUE_KEY);
	}

	private static DomainException codeTaken(String code) {
		return new DomainException(BranchErrorCode.BRANCH_CODE_TAKEN, "Branch code taken: " + code, code);
	}

}
