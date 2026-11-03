package vn.hayi.shop.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;

import vn.hayi.shop.shared.error.RuleViolationsException;
import vn.hayi.shop.shared.error.RuleViolationsException.Violation;

/** Value rules of a fulfillment config (Decision R3). */
class FulfillmentSettingsTest {

	@Test
	void defaultsAreValid() {
		assertThatNoException().isThrownBy(FulfillmentSettings.DEFAULTS::validated);
	}

	@Test
	void boundaryValuesAreValid() {
		assertThatNoException().isThrownBy(
				() -> new FulfillmentSettings(true, true, 0, 30, 0, 0, 0, 0, 1, 1, "x".repeat(500)).validated());
	}

	@Test
	void slotLengthIsFifteenOrThirtyOnly() {
		for (int minutes : new int[] { 0, 10, 20, 45, 60 }) {
			RuleViolationsException refused = catchThrowableOfType(RuleViolationsException.class,
					() -> new FulfillmentSettings(true, false, 7, minutes, 180, 0, null, null, null, null, null)
						.validated());
			assertThat(refused.violations()).containsExactly(new Violation("slotDurationMinutes", "NOT_ALLOWED_VALUE"));
		}
	}

	@Test
	void diffKeepsOnlyChangedFieldsByApiName() {
		FulfillmentSettings changed = new FulfillmentSettings(true, false, 7, 15, 180, 30, null, null, null, null,
				null);

		assertThat(changed.diffFrom(FulfillmentSettings.DEFAULTS).get(0)).containsOnlyKeys("isEnabled",
				"minLeadMinutes");
		assertThat(changed.diffFrom(changed).get(0)).isEmpty();
	}

}
