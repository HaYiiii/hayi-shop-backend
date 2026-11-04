package vn.hayi.shop.fulfillment.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.audit.api.AuditEntry;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.audit.api.AuditSource;
import vn.hayi.shop.branch.api.BranchDirectory;
import vn.hayi.shop.fulfillment.domain.BranchFulfillmentConfig;
import vn.hayi.shop.fulfillment.domain.FulfillmentSettings;
import vn.hayi.shop.fulfillment.domain.FulfillmentType;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;

/**
 * Reads and replaces a Branch's fulfillment configs (API 09 §2, Decision R). The Branch is checked through
 * {@code branch.api}; every change is audited in the same transaction.
 */
@Service
public class FulfillmentConfigService {

	private final FulfillmentConfigStore configs;

	private final BranchDirectory branches;

	private final AuditRecorder audit;

	private final Clock clock;

	private final OverbookingCheck overbooking;

	FulfillmentConfigService(FulfillmentConfigStore configs, BranchDirectory branches, AuditRecorder audit,
			Clock clock, OverbookingCheck overbooking) {
		this.configs = configs;
		this.branches = branches;
		this.audit = audit;
		this.clock = clock;
		this.overbooking = overbooking;
	}

	/** Every type the MVP takes (only {@code PICKUP}), saved or still the default. */
	@Transactional(readOnly = true)
	public List<FulfillmentConfigView> list(UUID branchId) {
		this.branches.requireExists(branchId);
		return List.of(view(FulfillmentType.PICKUP, this.configs
			.find(branchId, FulfillmentType.PICKUP)
			.orElse(null)));
	}

	/**
	 * Replaces one config if it is still at {@code expected}; {@code "v0"} creates it. A concurrent first save loses on
	 * the unique key and gets {@code VERSION_CONFLICT}, like any lost race (Decision R2).
	 */
	@Transactional
	public FulfillmentConfigView replace(UUID branchId, String fulfillmentType, FulfillmentRules rules,
			ExpectedVersion expected, ActorContext actor, String ipAddress, String userAgent) {
		FulfillmentSettings settings = new FulfillmentSettings(rules.isEnabled(), rules.asapEnabled(),
				rules.maxAdvanceDays(), rules.slotDurationMinutes(), rules.sameDayCutoffMinutes(), rules.minLeadMinutes(),
				rules.groupOrderMinLeadMinutes(), rules.customerCancelCutoffMinutes(), rules.defaultSlotMaxOrders(),
				rules.defaultSlotMaxItems(), rules.pickupInstructions());
		this.branches.requireExists(branchId);
		FulfillmentType type = FulfillmentConfigStore.supportedType(fulfillmentType);
		this.branches.requireConfigurable(branchId);
		settings.validated();
		Optional<BranchFulfillmentConfig> existing = this.configs.find(branchId, type);
		expected.verify(existing.map(BranchFulfillmentConfig::getVersion).orElse(0L));
		FulfillmentSettings previous = existing.map(BranchFulfillmentConfig::settings)
			.orElse(FulfillmentSettings.DEFAULTS);
		BranchFulfillmentConfig config;
		if (existing.isEmpty()) {
			if (settings.equals(FulfillmentSettings.DEFAULTS)) {
				return view(type, null);
			}
			config = BranchFulfillmentConfig.create(branchId, type, settings, this.clock.instant());
		}
		else {
			config = existing.get();
			if (!config.replace(settings, this.clock.instant())) {
				return withOverbooked(view(type, config), config);
			}
		}
		this.configs.save(config);
		List<Map<String, Object>> diff = settings.diffFrom(previous);
		this.audit.record(AuditEntry.of(actor, AuditSource.BACKOFFICE_WEB, "FULFILLMENT_CONFIG_UPDATE",
				"FULFILLMENT_CONFIG", config.getId())
			.withBranch(branchId)
			.withOldValue(diff.get(0))
			.withNewValue(diff.get(1))
			.withClient(ipAddress, userAgent));
		return withOverbooked(view(type, config), config);
	}

	/** After a change, the slots whose claims the new capacity no longer covers (Decision V2). */
	private FulfillmentConfigView withOverbooked(FulfillmentConfigView view, BranchFulfillmentConfig config) {
		return new FulfillmentConfigView(view.fulfillmentType(), view.rules(), view.version(),
				this.overbooking.after(config));
	}

	private static FulfillmentConfigView view(FulfillmentType type, BranchFulfillmentConfig config) {
		return (config != null)
				? new FulfillmentConfigView(type.name(), rules(config.settings()), config.getVersion(), List.of())
				: new FulfillmentConfigView(type.name(), rules(FulfillmentSettings.DEFAULTS), 0, List.of());
	}

	private static FulfillmentRules rules(FulfillmentSettings settings) {
		return new FulfillmentRules(settings.enabled(), settings.asapEnabled(), settings.maxAdvanceDays(),
				settings.slotDurationMinutes(), settings.sameDayCutoffMinutes(), settings.minLeadMinutes(),
				settings.groupOrderMinLeadMinutes(), settings.customerCancelCutoffMinutes(),
				settings.defaultSlotMaxOrders(), settings.defaultSlotMaxItems(), settings.pickupInstructions());
	}

}
