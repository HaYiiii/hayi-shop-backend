package vn.hayi.shop.branch.web.publicapi;

import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.branch.api.OpeningInterval;
import vn.hayi.shop.branch.application.BranchView;
import vn.hayi.shop.branch.application.PublicBranchService;
import vn.hayi.shop.branch.application.PublicBranchView;
import vn.hayi.shop.platform.web.spec.publicapi.api.PublicBranchesApi;
import vn.hayi.shop.platform.web.spec.publicapi.model.OpeningRangeDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.PublicAddressDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.PublicBranchDetailDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.PublicBranchDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.PublicBranchListDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.PublicFulfillmentTypeDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.PublicGeoPointDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.PublicHourOverrideDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.WeeklyOpeningDto;

/** Public Branch endpoints (API 02 §1, Decision U); cached five minutes, no sign-in. */
@RestController
class PublicBranchesController implements PublicBranchesApi {

	private static final CacheControl FIVE_MINUTES = CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic();

	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	private final PublicBranchService branches;

	PublicBranchesController(PublicBranchService branches) {
		this.branches = branches;
	}

	@Override
	public ResponseEntity<PublicBranchListDto> listPublicBranches() {
		List<PublicBranchDto> items = this.branches.listActive().stream()
			.map(PublicBranchesController::toDto)
			.toList();
		return ResponseEntity.ok().cacheControl(FIVE_MINUTES).body(new PublicBranchListDto(items));
	}

	@Override
	public ResponseEntity<PublicBranchDetailDto> getPublicBranch(UUID branchId) {
		PublicBranchView view = this.branches.getActive(branchId);
		BranchView branch = view.branch();
		PublicBranchDetailDto detail = new PublicBranchDetailDto(branch.branchId(), branch.branchCode(), branch.name(),
				address(branch), location(branch), branch.phone(), branch.timezone(), view.openNow(),
				ranges(view.todayHours()), types(view), utc(view.earliestPickupAt()),
				view.weeklyHours().stream()
					.map(hours -> new WeeklyOpeningDto(hours.dayOfWeek(), text(hours.open()), text(hours.close())))
					.toList(),
				view.overrides().stream()
					.map(override -> new PublicHourOverrideDto(override.date(), override.isClosed(),
							ranges(override.intervals())))
					.toList());
		return ResponseEntity.ok().cacheControl(FIVE_MINUTES).body(detail);
	}

	private static PublicBranchDto toDto(PublicBranchView view) {
		BranchView branch = view.branch();
		return new PublicBranchDto(branch.branchId(), branch.branchCode(), branch.name(), address(branch),
				location(branch), branch.phone(), branch.timezone(), view.openNow(), ranges(view.todayHours()),
				types(view), utc(view.earliestPickupAt()));
	}

	private static PublicAddressDto address(BranchView branch) {
		return new PublicAddressDto(branch.addressLine(), branch.ward(), branch.district(), branch.province());
	}

	private static PublicGeoPointDto location(BranchView branch) {
		return (branch.lat() != null) ? new PublicGeoPointDto(branch.lat(), branch.lng()) : null;
	}

	private static List<PublicFulfillmentTypeDto> types(PublicBranchView view) {
		return view.fulfillmentTypes().stream().map(PublicFulfillmentTypeDto::fromValue).toList();
	}

	private static List<OpeningRangeDto> ranges(List<OpeningInterval> intervals) {
		return intervals.stream().map(range -> new OpeningRangeDto(text(range.open()), text(range.close()))).toList();
	}

	private static OffsetDateTime utc(Instant instant) {
		return (instant != null) ? instant.atOffset(ZoneOffset.UTC) : null;
	}

	private static String text(LocalTime time) {
		return time.format(HH_MM);
	}

}
