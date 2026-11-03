package vn.hayi.shop.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.branch.api.BranchDirectory;
import vn.hayi.shop.branch.application.BranchService;
import vn.hayi.shop.branch.application.NewBranch;
import vn.hayi.shop.fulfillment.domain.BranchFulfillmentConfig;
import vn.hayi.shop.fulfillment.domain.BranchFulfillmentConfigRepository;
import vn.hayi.shop.fulfillment.domain.FulfillmentType;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;

/**
 * The losing side of two concurrent first saves (Decision R2), forced deterministically: it read "no config yet", but
 * another save committed first, so its insert hits {@code uk_ful_branch_fulfillment_config__branch_type}.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class })
class FulfillmentConfigFirstSaveRaceTest {

	static final ActorContext OWNER = ActorContext.staff(UUID.randomUUID(), null);

	static final FulfillmentRules RULES = new FulfillmentRules(true, false, 7, 30, 180, 30, null, null, 15, 40, null);

	@Autowired
	FulfillmentConfigService service;

	@Autowired
	BranchFulfillmentConfigRepository configs;

	@Autowired
	BranchDirectory directory;

	@Autowired
	AuditRecorder audit;

	@Autowired
	BranchService branches;

	@Test
	void firstSaveThatLostTheRaceIsAVersionConflict() {
		UUID branchId = this.branches.create(new NewBranch("T-" + UUID.randomUUID().toString().substring(0, 8)
			.toUpperCase(), "HaYi test", "1 Lê Lợi", null, null, "TP.HCM", null, null, null, null), OWNER, null, null)
			.branchId();
		this.service.replace(branchId, "PICKUP", RULES, new ExpectedVersion(0), OWNER, null, null);
		FulfillmentConfigService late = new FulfillmentConfigService(staleReads(this.configs), this.directory,
				this.audit, Clock.systemUTC());

		assertThatThrownBy(() -> late.replace(branchId, "PICKUP",
				new FulfillmentRules(true, false, 3, 15, 180, 30, null, null, null, null, null), new ExpectedVersion(0),
				OWNER, null, null))
			.isInstanceOfSatisfying(DomainException.class,
					ex -> assertThat(ex.code()).isEqualTo(CommonErrorCode.VERSION_CONFLICT));
	}

	/** Reads as if the other save had not committed yet; writes go to the real repository. */
	private static BranchFulfillmentConfigRepository staleReads(BranchFulfillmentConfigRepository real) {
		return new BranchFulfillmentConfigRepository() {

			@Override
			public BranchFulfillmentConfig saveAndFlush(BranchFulfillmentConfig config) {
				return real.saveAndFlush(config);
			}

			@Override
			public Optional<BranchFulfillmentConfig> findByBranchIdAndFulfillmentType(UUID branchId,
					FulfillmentType type) {
				return Optional.empty();
			}

		};
	}

}
