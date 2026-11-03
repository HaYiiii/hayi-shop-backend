package vn.hayi.shop.fulfillment.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.audit.api.AuditEntry;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.audit.api.AuditSource;
import vn.hayi.shop.branch.api.BranchDirectory;
import vn.hayi.shop.fulfillment.domain.BranchFulfillmentConfig;
import vn.hayi.shop.fulfillment.domain.BranchFulfillmentConfigRepository;
import vn.hayi.shop.fulfillment.domain.FulfillmentErrorCode;
import vn.hayi.shop.fulfillment.domain.FulfillmentSettings;
import vn.hayi.shop.fulfillment.domain.FulfillmentType;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.error.RuleViolationsException;
import vn.hayi.shop.shared.error.RuleViolationsException.Violation;

/**
 * Reads and replaces a Branch's fulfillment configs (API 09 §2, Decision R). The Branch is checked through
 * {@code branch.api}; every change is audited in the same transaction.
 */
@Service
public class FulfillmentConfigService {

	private static final String UNIQUE_KEY = "uk_ful_branch_fulfillment_config__branch_type";

	private final BranchFulfillmentConfigRepository configs;

	private final BranchDirectory branches;

	private final AuditRecorder audit;

	private final Clock clock;

	FulfillmentConfigService(BranchFulfillmentConfigRepository configs, BranchDirectory branches, AuditRecorder audit,
			Clock clock) {
		this.configs = configs;
		this.branches = branches;
		this.audit = audit;
		this.clock = clock;
	}

	/** Every type the MVP takes (only {@code PICKUP}), saved or still the default. */
	@Transactional(readOnly = true)
	public List<FulfillmentConfigView> list(UUID branchId) {
		this.branches.requireExists(branchId);
		return List.of(view(FulfillmentType.PICKUP, this.configs
			.findByBranchIdAndFulfillmentType(branchId, FulfillmentType.PICKUP)
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
		FulfillmentType type = supportedType(fulfillmentType);
		this.branches.requireConfigurable(branchId);
		settings.validated();
		Optional<BranchFulfillmentConfig> existing = this.configs.findByBranchIdAndFulfillmentType(branchId, type);
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
				return view(type, config);
			}
		}
		save(config);
		List<Map<String, Object>> diff = settings.diffFrom(previous);
		this.audit.record(AuditEntry.of(actor, AuditSource.BACKOFFICE_WEB, "FULFILLMENT_CONFIG_UPDATE",
				"FULFILLMENT_CONFIG", config.getId())
			.withBranch(branchId)
			.withOldValue(diff.get(0))
			.withNewValue(diff.get(1))
			.withClient(ipAddress, userAgent));
		return view(type, config);
	}

	private void save(BranchFulfillmentConfig config) {
		try {
			this.configs.saveAndFlush(config);
		}
		catch (DataIntegrityViolationException ex) {
			String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
			if (message != null && message.contains(UNIQUE_KEY)) {
				throw new DomainException(CommonErrorCode.VERSION_CONFLICT,
						"Concurrent first save of " + config.getFulfillmentType() + " at " + config.getBranchId());
			}
			throw ex;
		}
	}

	/** Exact {@code PICKUP} only in the MVP; anything else is a rule violation, not a malformed request (R3). */
	private static FulfillmentType supportedType(String value) {
		return FulfillmentType.parse(value)
			.filter(FulfillmentType::supportedInMvp)
			.orElseThrow(() -> new RuleViolationsException(FulfillmentErrorCode.FULFILLMENT_CONFIG_INVALID,
					List.of(new Violation("fulfillmentType", "NOT_SUPPORTED"))));
	}

	private static FulfillmentConfigView view(FulfillmentType type, BranchFulfillmentConfig config) {
		return (config != null) ? new FulfillmentConfigView(type.name(), rules(config.settings()), config.getVersion())
				: new FulfillmentConfigView(type.name(), rules(FulfillmentSettings.DEFAULTS), 0);
	}

	private static FulfillmentRules rules(FulfillmentSettings settings) {
		return new FulfillmentRules(settings.enabled(), settings.asapEnabled(), settings.maxAdvanceDays(),
				settings.slotDurationMinutes(), settings.sameDayCutoffMinutes(), settings.minLeadMinutes(),
				settings.groupOrderMinLeadMinutes(), settings.customerCancelCutoffMinutes(),
				settings.defaultSlotMaxOrders(), settings.defaultSlotMaxItems(), settings.pickupInstructions());
	}

}
