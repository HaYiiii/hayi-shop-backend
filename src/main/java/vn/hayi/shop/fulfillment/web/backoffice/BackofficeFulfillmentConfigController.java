package vn.hayi.shop.fulfillment.web.backoffice;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.fulfillment.application.FulfillmentConfigService;
import vn.hayi.shop.fulfillment.application.FulfillmentConfigView;
import vn.hayi.shop.fulfillment.application.FulfillmentRules;
import vn.hayi.shop.fulfillment.application.OverrideInput;
import vn.hayi.shop.fulfillment.application.PickupScheduleService;
import vn.hayi.shop.fulfillment.application.PickupScheduleView;
import vn.hayi.shop.fulfillment.application.PickupWindowInput;
import vn.hayi.shop.fulfillment.application.ScheduleWindowInput;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.Permission;
import vn.hayi.shop.platform.security.RequiresPermission;
import vn.hayi.shop.platform.web.VersionTags;
import vn.hayi.shop.platform.web.spec.backoffice.api.FulfillmentConfigsApi;
import vn.hayi.shop.platform.web.spec.backoffice.model.FulfillmentConfigDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.FulfillmentConfigInputDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.FulfillmentConfigListDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.PickupScheduleDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.ScheduleOverrideDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.ScheduleOverrideRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.ScheduleWindowDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.WeeklyScheduleReplaceRequestDto;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;

/** Backoffice fulfillment config and pickup schedule endpoints (API 09 §2.1–2.2); maps the generated contract. */
@RestController
class BackofficeFulfillmentConfigController implements FulfillmentConfigsApi {

	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	private final FulfillmentConfigService configs;

	private final PickupScheduleService schedules;

	private final HttpServletRequest request;

	BackofficeFulfillmentConfigController(FulfillmentConfigService configs, PickupScheduleService schedules,
			HttpServletRequest request) {
		this.configs = configs;
		this.schedules = schedules;
		this.request = request;
	}

	@Override
	@RequiresPermission(Permission.BRANCH_VIEW)
	public ResponseEntity<PickupScheduleDto> getFulfillmentSchedule(UUID branchId, String fulfillmentType) {
		return scheduleResponse(this.schedules.get(branchId, fulfillmentType));
	}

	@Override
	@RequiresPermission(Permission.FULFILLMENT_CONFIG_MANAGE)
	public ResponseEntity<PickupScheduleDto> replaceFulfillmentWeeklySchedule(UUID branchId, String fulfillmentType,
			WeeklyScheduleReplaceRequestDto body, String ifMatch, String xCsrfToken) {
		ExpectedVersion expected = VersionTags.require(ifMatch);
		List<ScheduleWindowInput> week = body.getWeekly().stream()
			.map(window -> new ScheduleWindowInput(window.getDayOfWeek(), time(window.getStart()),
					time(window.getEnd()), window.getMaxOrders(), window.getMaxItems()))
			.toList();
		ClientInfo client = ClientInfo.from(this.request);
		return scheduleResponse(this.schedules.replaceWeekly(branchId, fulfillmentType, week, expected, actor(),
				client.ipAddress(), client.userAgent()));
	}

	@Override
	@RequiresPermission(Permission.FULFILLMENT_CONFIG_MANAGE)
	public ResponseEntity<PickupScheduleDto> putFulfillmentOverride(UUID branchId, String fulfillmentType,
			ScheduleOverrideRequestDto body, String ifMatch, String xCsrfToken) {
		ExpectedVersion expected = VersionTags.optional(ifMatch);
		OverrideInput input = new OverrideInput(body.getDate(), body.getIsClosed(),
				body.getIntervals().stream()
					.map(window -> new PickupWindowInput(time(window.getStart()), time(window.getEnd())))
					.toList(),
				body.getMaxOrders(), body.getMaxItems(), body.getReason());
		ClientInfo client = ClientInfo.from(this.request);
		return scheduleResponse(this.schedules.putOverride(branchId, fulfillmentType, input, expected, actor(),
				client.ipAddress(), client.userAgent()));
	}

	@Override
	@RequiresPermission(Permission.FULFILLMENT_CONFIG_MANAGE)
	public ResponseEntity<PickupScheduleDto> deleteFulfillmentOverride(UUID branchId, String fulfillmentType,
			UUID overrideId, String ifMatch, String xCsrfToken) {
		ExpectedVersion expected = VersionTags.optional(ifMatch);
		ClientInfo client = ClientInfo.from(this.request);
		return scheduleResponse(this.schedules.deleteOverride(branchId, fulfillmentType, overrideId, expected,
				actor(), client.ipAddress(), client.userAgent()));
	}

	private static ResponseEntity<PickupScheduleDto> scheduleResponse(PickupScheduleView view) {
		List<ScheduleWindowDto> weekly = view.weekly().stream()
			.map(window -> new ScheduleWindowDto(window.dayOfWeek(), text(window.start()), text(window.end()))
				.maxOrders(window.maxOrders())
				.maxItems(window.maxItems()))
			.toList();
		List<ScheduleOverrideDto> overrides = view.overrides().stream()
			.map(override -> new ScheduleOverrideDto(override.overrideId(), override.date(), override.isClosed(),
					text(override.start()), text(override.end()), override.maxOrders(), override.maxItems(),
					override.reason()))
			.toList();
		return ResponseEntity.ok()
			.eTag(VersionTags.of(view.version()))
			.body(new PickupScheduleDto(weekly, overrides));
	}

	private static ActorContext actor() {
		return ActorContext.staff(AuthenticatedSession.require().userId(), null);
	}

	/** The spec pattern already guarantees {@code HH:mm} between {@code 00:00} and {@code 23:59}. */
	private static LocalTime time(String hhmm) {
		return LocalTime.parse(hhmm, HH_MM);
	}

	private static String text(LocalTime time) {
		return (time != null) ? time.format(HH_MM) : null;
	}

	@Override
	@RequiresPermission(Permission.BRANCH_VIEW)
	public ResponseEntity<FulfillmentConfigListDto> listFulfillmentConfigs(UUID branchId) {
		return ResponseEntity.ok(new FulfillmentConfigListDto(
				this.configs.list(branchId).stream().map(BackofficeFulfillmentConfigController::toDto).toList()));
	}

	@Override
	@RequiresPermission(Permission.FULFILLMENT_CONFIG_MANAGE)
	public ResponseEntity<FulfillmentConfigDto> replaceFulfillmentConfig(UUID branchId, String fulfillmentType,
			FulfillmentConfigInputDto body, String ifMatch, String xCsrfToken) {
		ExpectedVersion expected = VersionTags.require(ifMatch);
		ClientInfo client = ClientInfo.from(this.request);
		FulfillmentConfigView config = this.configs.replace(branchId, fulfillmentType, toRules(body), expected,
				ActorContext.staff(AuthenticatedSession.require().userId(), null), client.ipAddress(),
				client.userAgent());
		return ResponseEntity.ok().eTag(VersionTags.of(config.version())).body(toDto(config));
	}

	private static FulfillmentRules toRules(FulfillmentConfigInputDto body) {
		return new FulfillmentRules(body.getIsEnabled(), body.getAsapEnabled(), body.getMaxAdvanceDays(),
				body.getSlotDurationMinutes(), body.getSameDayCutoffMinutes(), body.getMinLeadMinutes(),
				body.getGroupOrderMinLeadMinutes(), body.getCustomerCancelCutoffMinutes(),
				body.getDefaultSlotMaxOrders(), body.getDefaultSlotMaxItems(), body.getPickupInstructions());
	}

	private static FulfillmentConfigDto toDto(FulfillmentConfigView config) {
		FulfillmentRules settings = config.rules();
		return new FulfillmentConfigDto(FulfillmentConfigDto.FulfillmentTypeEnum.fromValue(config.fulfillmentType()),
				settings.isEnabled(), settings.asapEnabled(), settings.maxAdvanceDays(), settings.slotDurationMinutes(),
				settings.sameDayCutoffMinutes(), settings.minLeadMinutes(), settings.groupOrderMinLeadMinutes(),
				settings.customerCancelCutoffMinutes(), settings.defaultSlotMaxOrders(), settings.defaultSlotMaxItems(),
				settings.pickupInstructions(), config.version());
	}

}
