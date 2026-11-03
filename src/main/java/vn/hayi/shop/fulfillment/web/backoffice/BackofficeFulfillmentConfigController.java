package vn.hayi.shop.fulfillment.web.backoffice;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.fulfillment.application.FulfillmentConfigService;
import vn.hayi.shop.fulfillment.application.FulfillmentConfigView;
import vn.hayi.shop.fulfillment.application.FulfillmentRules;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.Permission;
import vn.hayi.shop.platform.security.RequiresPermission;
import vn.hayi.shop.platform.web.VersionTags;
import vn.hayi.shop.platform.web.spec.backoffice.api.FulfillmentConfigsApi;
import vn.hayi.shop.platform.web.spec.backoffice.model.FulfillmentConfigDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.FulfillmentConfigInputDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.FulfillmentConfigListDto;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;

/** Backoffice fulfillment config endpoints (API 09 §2.1); maps the generated contract to the service. */
@RestController
class BackofficeFulfillmentConfigController implements FulfillmentConfigsApi {

	private final FulfillmentConfigService configs;

	private final HttpServletRequest request;

	BackofficeFulfillmentConfigController(FulfillmentConfigService configs, HttpServletRequest request) {
		this.configs = configs;
		this.request = request;
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
		return new FulfillmentConfigDto(settings.isEnabled(), settings.asapEnabled(), settings.maxAdvanceDays(),
				settings.slotDurationMinutes(), settings.sameDayCutoffMinutes(), settings.minLeadMinutes(),
				settings.groupOrderMinLeadMinutes(), settings.customerCancelCutoffMinutes(),
				settings.defaultSlotMaxOrders(), settings.defaultSlotMaxItems(), settings.pickupInstructions(),
				FulfillmentConfigDto.FulfillmentTypeEnum.fromValue(config.fulfillmentType()), config.version());
	}

}
