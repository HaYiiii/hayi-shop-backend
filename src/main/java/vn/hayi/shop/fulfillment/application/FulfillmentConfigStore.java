package vn.hayi.shop.fulfillment.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import vn.hayi.shop.fulfillment.domain.BranchFulfillmentConfig;
import vn.hayi.shop.fulfillment.domain.BranchFulfillmentConfigRepository;
import vn.hayi.shop.fulfillment.domain.FulfillmentErrorCode;
import vn.hayi.shop.fulfillment.domain.FulfillmentSettings;
import vn.hayi.shop.fulfillment.domain.FulfillmentType;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.error.RuleViolationsException;
import vn.hayi.shop.shared.error.RuleViolationsException.Violation;

/**
 * Loads and saves a Branch's fulfillment config, always by {@code (branchId, type)} (Decision S7). A config not saved
 * yet is the ERD default at version 0 (R1); saving it creates it at version 1, and a concurrent first save loses on
 * the unique key with {@code VERSION_CONFLICT} (R2).
 */
@Component
class FulfillmentConfigStore {

	private static final String UNIQUE_KEY = "uk_ful_branch_fulfillment_config__branch_type";

	private final BranchFulfillmentConfigRepository configs;

	FulfillmentConfigStore(BranchFulfillmentConfigRepository configs) {
		this.configs = configs;
	}

	Optional<BranchFulfillmentConfig> find(UUID branchId, FulfillmentType type) {
		return this.configs.findByBranchIdAndFulfillmentType(branchId, type);
	}

	/** The saved config, or a new default one that is only stored when {@link #save} is called. */
	BranchFulfillmentConfig findOrDefault(UUID branchId, FulfillmentType type, Instant now) {
		return find(branchId, type)
			.orElseGet(() -> BranchFulfillmentConfig.create(branchId, type, FulfillmentSettings.DEFAULTS, now));
	}

	/** {@code 0} for a config not saved yet. */
	static long versionOf(BranchFulfillmentConfig config) {
		return config.isNew() ? 0 : config.getVersion();
	}

	void save(BranchFulfillmentConfig config) {
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
	static FulfillmentType supportedType(String value) {
		return FulfillmentType.parse(value)
			.filter(FulfillmentType::supportedInMvp)
			.orElseThrow(() -> new RuleViolationsException(FulfillmentErrorCode.FULFILLMENT_CONFIG_INVALID,
					List.of(new Violation("fulfillmentType", "NOT_SUPPORTED"))));
	}

}
