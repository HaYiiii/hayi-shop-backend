package vn.hayi.shop.branch.web.backoffice;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import vn.hayi.shop.branch.api.OpeningInterval;
import vn.hayi.shop.branch.application.BranchChanges;
import vn.hayi.shop.branch.application.BranchHoursService;
import vn.hayi.shop.branch.application.BranchHoursView;
import vn.hayi.shop.branch.application.HourOverrideRequest;
import vn.hayi.shop.branch.application.BranchService;
import vn.hayi.shop.branch.application.BranchView;
import vn.hayi.shop.branch.application.NewBranch;
import vn.hayi.shop.branch.application.WeeklyIntervalRequest;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.Permission;
import vn.hayi.shop.platform.security.PermissionEvaluator;
import vn.hayi.shop.platform.security.PermissionScope;
import vn.hayi.shop.platform.security.RequiresPermission;
import vn.hayi.shop.platform.security.RequiresStepUp;
import vn.hayi.shop.platform.web.MergePatch;
import vn.hayi.shop.platform.web.VersionTags;
import vn.hayi.shop.platform.web.spec.backoffice.api.BranchesApi;
import vn.hayi.shop.platform.web.spec.backoffice.model.AddressDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.AddressInputDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchBusinessHoursDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchClosureCheckDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchCommitmentTypeDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchConflictDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchCreateRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchListDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchStatusDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.BusinessHoursReplaceRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.GeoPointDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.HourOverrideDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.HourOverrideRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.TimeIntervalDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.WeeklyIntervalDto;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.patch.Change;

/** Backoffice Branch endpoints (API 09 §1); maps the generated contract to {@link BranchService}. */
@RestController
class BackofficeBranchController implements BranchesApi {

	private static final String BRANCHES = "/api/backoffice/v1/branches/";

	private static final BigDecimal LAT_MIN = BigDecimal.valueOf(-90);

	private static final BigDecimal LAT_MAX = BigDecimal.valueOf(90);

	private static final BigDecimal LNG_MIN = BigDecimal.valueOf(-180);

	private static final BigDecimal LNG_MAX = BigDecimal.valueOf(180);

	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	private final BranchService branches;

	private final BranchHoursService hours;

	private final PermissionEvaluator permissions;

	private final HttpServletRequest request;

	BackofficeBranchController(BranchService branches, BranchHoursService hours, PermissionEvaluator permissions,
			HttpServletRequest request) {
		this.branches = branches;
		this.hours = hours;
		this.permissions = permissions;
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
		// A cross-Branch view filters its own data to the caller's scope (Decision M1).
		PermissionScope scope = this.permissions.scopeOf(AuthenticatedSession.require().userId(),
				Permission.BRANCH_VIEW);
		Set<String> statuses = (status != null)
				? status.stream().map(BranchStatusDto::getValue).collect(Collectors.toSet()) : Set.of();
		List<BranchDto> items = this.branches.list(scope, statuses).stream()
			.map(BackofficeBranchController::toDto)
			.toList();
		return ResponseEntity.ok(new BranchListDto(items));
	}

	@Override
	@RequiresPermission(Permission.BRANCH_MANAGE)
	public ResponseEntity<BranchDto> updateBranch(UUID branchId, JsonNode body, String ifMatch, String xCsrfToken) {
		ExpectedVersion expected = VersionTags.require(ifMatch);
		BranchChanges changes = toChanges(body);
		AuthenticatedSession session = AuthenticatedSession.require();
		ClientInfo client = ClientInfo.from(this.request);
		BranchView branch = this.branches.update(branchId, changes, expected,
				ActorContext.staff(session.userId(), null), client.ipAddress(), client.userAgent());
		return ResponseEntity.ok().eTag(eTag(branch)).body(toDto(branch));
	}

	@Override
	@RequiresPermission(Permission.BRANCH_MANAGE)
	public ResponseEntity<BranchDto> activateBranch(UUID branchId, String ifMatch, String xCsrfToken) {
		return lifecycle(ifMatch, (expected, actor, client) -> this.branches.activate(branchId, expected, actor,
				client.ipAddress(), client.userAgent()));
	}

	@Override
	@RequiresPermission(Permission.BRANCH_MANAGE)
	public ResponseEntity<BranchDto> deactivateBranch(UUID branchId, String ifMatch, String xCsrfToken) {
		return lifecycle(ifMatch, (expected, actor, client) -> this.branches.deactivate(branchId, expected, actor,
				client.ipAddress(), client.userAgent()));
	}

	/** Closing is terminal, so it needs a fresh step-up even though {@code BRANCH_MANAGE} is not S (M5, P4). */
	@Override
	@RequiresPermission(Permission.BRANCH_MANAGE)
	@RequiresStepUp
	public ResponseEntity<BranchDto> closeBranch(UUID branchId, String ifMatch, String xCsrfToken) {
		return lifecycle(ifMatch, (expected, actor, client) -> this.branches.close(branchId, expected, actor,
				client.ipAddress(), client.userAgent()));
	}

	@Override
	@RequiresPermission(Permission.BRANCH_MANAGE)
	public ResponseEntity<BranchClosureCheckDto> getBranchClosureCheck(UUID branchId) {
		List<BranchConflictDto> conflicts = this.branches.closureCheck(branchId).stream()
			.map(conflict -> new BranchConflictDto(BranchCommitmentTypeDto.valueOf(conflict.type().name()),
					conflict.count()))
			.toList();
		return ResponseEntity.ok(new BranchClosureCheckDto(conflicts));
	}

	@Override
	@RequiresPermission(Permission.BRANCH_VIEW)
	public ResponseEntity<BranchBusinessHoursDto> getBranchBusinessHours(UUID branchId) {
		return hoursResponse(this.hours.get(branchId));
	}

	@Override
	@RequiresPermission(Permission.BRANCH_MANAGE)
	public ResponseEntity<BranchBusinessHoursDto> replaceBranchBusinessHours(UUID branchId,
			BusinessHoursReplaceRequestDto body, String ifMatch, String xCsrfToken) {
		ExpectedVersion expected = VersionTags.require(ifMatch);
		List<WeeklyIntervalRequest> week = body.getWeekly().stream()
			.map(hours -> new WeeklyIntervalRequest(hours.getDayOfWeek(), time(hours.getOpen()),
					time(hours.getClose())))
			.toList();
		ClientInfo client = ClientInfo.from(this.request);
		return hoursResponse(this.hours.replaceWeekly(branchId, week, expected, actor(), client.ipAddress(),
				client.userAgent()));
	}

	@Override
	@RequiresPermission(Permission.BRANCH_MANAGE)
	public ResponseEntity<BranchBusinessHoursDto> putBranchHourOverride(UUID branchId, HourOverrideRequestDto body,
			String ifMatch, String xCsrfToken) {
		ExpectedVersion expected = VersionTags.optional(ifMatch);
		HourOverrideRequest override = new HourOverrideRequest(body.getDate(), body.getIsClosed(),
				body.getIntervals().stream()
					.map(range -> new OpeningInterval(time(range.getOpen()), time(range.getClose())))
					.toList(),
				body.getReason());
		ClientInfo client = ClientInfo.from(this.request);
		return hoursResponse(this.hours.putOverride(branchId, override, expected, actor(), client.ipAddress(),
				client.userAgent()));
	}

	@Override
	@RequiresPermission(Permission.BRANCH_MANAGE)
	public ResponseEntity<BranchBusinessHoursDto> deleteBranchHourOverride(UUID branchId, LocalDate date,
			String ifMatch, String xCsrfToken) {
		ExpectedVersion expected = VersionTags.optional(ifMatch);
		ClientInfo client = ClientInfo.from(this.request);
		return hoursResponse(this.hours.deleteOverride(branchId, date, expected, actor(), client.ipAddress(),
				client.userAgent()));
	}

	private static ResponseEntity<BranchBusinessHoursDto> hoursResponse(BranchHoursView view) {
		List<WeeklyIntervalDto> weekly = view.weekly().stream()
			.map(hours -> new WeeklyIntervalDto(hours.dayOfWeek(), text(hours.open()), text(hours.close())))
			.toList();
		List<HourOverrideDto> overrides = view.overrides().stream()
			.map(override -> new HourOverrideDto(override.date(), override.isClosed(),
					override.intervals().stream()
						.map(range -> new TimeIntervalDto(text(range.open()), text(range.close())))
						.toList(),
					override.reason()))
			.toList();
		return ResponseEntity.ok()
			.eTag(VersionTags.of(view.version()))
			.body(new BranchBusinessHoursDto(weekly, overrides));
	}

	private static ActorContext actor() {
		return ActorContext.staff(AuthenticatedSession.require().userId(), null);
	}

	/** The spec pattern already guarantees {@code HH:mm} between {@code 00:00} and {@code 23:59}. */
	private static LocalTime time(String hhmm) {
		return LocalTime.parse(hhmm, HH_MM);
	}

	private static String text(LocalTime time) {
		return time.format(HH_MM);
	}

	private ResponseEntity<BranchDto> lifecycle(String ifMatch, LifecycleCommand command) {
		ExpectedVersion expected = VersionTags.optional(ifMatch);
		AuthenticatedSession session = AuthenticatedSession.require();
		BranchView branch = command.run(expected, ActorContext.staff(session.userId(), null),
				ClientInfo.from(this.request));
		return ResponseEntity.ok().eTag(eTag(branch)).body(toDto(branch));
	}

	@FunctionalInterface
	private interface LifecycleCommand {

		BranchView run(ExpectedVersion expected, ActorContext actor, ClientInfo client);

	}

	/** Field limits follow ERD 03 and the {@code BranchPatch} schema; anything else in the body is refused. */
	private static BranchChanges toChanges(JsonNode body) {
		MergePatch patch = MergePatch.of(body);
		Change<String> name = patch.requiredText("name", 128);
		Address address = patch.mergedObject("address", nested -> new Address(nested.requiredText("line", 255),
				nested.optionalText("ward", 128), nested.optionalText("district", 128),
				nested.requiredText("province", 128)));
		if (address == null) {
			address = new Address(Change.keep(), Change.keep(), Change.keep(), Change.keep());
		}
		Change<BranchChanges.Location> location = patch.replacedObject("location",
				nested -> new BranchChanges.Location(nested.requiredDecimal("lat", LAT_MIN, LAT_MAX),
						nested.requiredDecimal("lng", LNG_MIN, LNG_MAX)));
		Change<String> phone = patch.optionalText("phone", 20);
		Change<String> email = patch.optionalEmail("email", 255);
		patch.validate();
		return new BranchChanges(name, address.line(), address.ward(), address.district(), address.province(),
				location, phone, email);
	}

	private record Address(Change<String> line, Change<String> ward, Change<String> district,
			Change<String> province) {
	}

	private static NewBranch toNewBranch(BranchCreateRequestDto body) {
		AddressInputDto address = body.getAddress();
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
		return VersionTags.of(branch.version());
	}

	private static OffsetDateTime utc(Instant instant) {
		return (instant != null) ? instant.atOffset(ZoneOffset.UTC) : null;
	}

}
