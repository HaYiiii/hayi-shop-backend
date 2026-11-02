package vn.hayi.shop.branch.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.audit.api.AuditEntry;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.audit.api.AuditSource;
import vn.hayi.shop.branch.domain.Branch;
import vn.hayi.shop.branch.domain.BranchAddress;
import vn.hayi.shop.branch.domain.BranchErrorCode;
import vn.hayi.shop.branch.domain.BranchRepository;
import vn.hayi.shop.branch.domain.GeoPoint;
import vn.hayi.shop.platform.security.PermissionScope;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.error.DomainException;

/** Creates and reads Branches (Branch §4.2–4.3, API 09 §1); every creation is audited in the same transaction. */
@Service
public class BranchService {

	private final BranchRepository branches;

	private final AuditRecorder audit;

	private final Clock clock;

	BranchService(BranchRepository branches, AuditRecorder audit, Clock clock) {
		this.branches = branches;
		this.audit = audit;
		this.clock = clock;
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
			throw codeTaken(code);
		}
		this.audit.record(AuditEntry.of(actor, AuditSource.BACKOFFICE_WEB, "BRANCH_CREATE", "BRANCH", branch.getId())
			.withBranch(branch.getId())
			.withNewValue(snapshot(branch))
			.withClient(ipAddress, userAgent));
		return BranchView.of(branch);
	}

	@Transactional(readOnly = true)
	public BranchView get(UUID branchId) {
		return this.branches.findById(branchId)
			.map(BranchView::of)
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

	private static DomainException codeTaken(String code) {
		return new DomainException(BranchErrorCode.BRANCH_CODE_TAKEN, "Branch code taken: " + code, code);
	}

}
