package vn.hayi.shop.branch.web.backoffice;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.branch.application.BranchService;
import vn.hayi.shop.branch.application.BranchView;
import vn.hayi.shop.branch.application.NewBranch;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.Permission;
import vn.hayi.shop.platform.security.RequiresPermission;
import vn.hayi.shop.platform.web.spec.backoffice.api.BranchesApi;
import vn.hayi.shop.platform.web.spec.backoffice.model.AddressDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchCreateRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchListDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchStatusDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.GeoPointDto;
import vn.hayi.shop.shared.actor.ActorContext;

/** Backoffice Branch endpoints (API 09 §1); maps the generated contract to {@link BranchService}. */
@RestController
class BackofficeBranchController implements BranchesApi {

	private static final String BRANCHES = "/api/backoffice/v1/branches/";

	private final BranchService branches;

	private final HttpServletRequest request;

	BackofficeBranchController(BranchService branches, HttpServletRequest request) {
		this.branches = branches;
		this.request = request;
	}

	@Override
	@RequiresPermission(Permission.BRANCH_MANAGE)
	public ResponseEntity<BranchDto> createBranch(BranchCreateRequestDto body, String xCsrfToken) {
		AuthenticatedSession session = AuthenticatedSession.require();
		ClientInfo client = ClientInfo.from(this.request);
		BranchView branch = this.branches.create(toNewBranch(body), ActorContext.staff(session.userId(), null),
				client.ipAddress(), client.userAgent());
		return ResponseEntity.created(URI.create(BRANCHES + branch.branchId())).eTag(eTag(branch)).body(toDto(branch));
	}

	@Override
	@RequiresPermission(Permission.BRANCH_VIEW)
	public ResponseEntity<BranchDto> getBranch(UUID branchId) {
		BranchView branch = this.branches.get(branchId);
		return ResponseEntity.ok().eTag(eTag(branch)).body(toDto(branch));
	}

	@Override
	@RequiresPermission(Permission.BRANCH_VIEW)
	public ResponseEntity<BranchListDto> listBranches(List<BranchStatusDto> status) {
		throw new UnsupportedOperationException("BE #38: next slice");
	}

	private static NewBranch toNewBranch(BranchCreateRequestDto body) {
		AddressDto address = body.getAddress();
		GeoPointDto location = body.getLocation();
		return new NewBranch(body.getBranchCode(), body.getName(), address.getLine(), address.getWard(),
				address.getDistrict(), address.getProvince(), (location != null) ? location.getLat() : null,
				(location != null) ? location.getLng() : null, body.getPhone(), body.getEmail());
	}

	private static BranchDto toDto(BranchView branch) {
		return new BranchDto(branch.branchId(), branch.branchCode(), branch.name(),
				BranchStatusDto.valueOf(branch.status()),
				new AddressDto(branch.addressLine(), branch.ward(), branch.district(), branch.province()),
				(branch.lat() != null) ? new GeoPointDto(branch.lat(), branch.lng()) : null, branch.phone(),
				branch.email(), branch.timezone(), utc(branch.activatedAt()), utc(branch.closedAt()),
				utc(branch.createdAt()), branch.version());
	}

	private static String eTag(BranchView branch) {
		return "\"v" + branch.version() + "\"";
	}

	private static OffsetDateTime utc(Instant instant) {
		return (instant != null) ? instant.atOffset(ZoneOffset.UTC) : null;
	}

}
