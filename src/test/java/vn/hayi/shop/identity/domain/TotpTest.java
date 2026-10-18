package vn.hayi.shop.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** TOTP of RFC 6238 with SHA-1, 6 digits, 30-second steps and ±1 step of drift (Decision L5). */
class TotpTest {

	/** The RFC 6238 Appendix B test secret ("12345678901234567890"). */
	private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

	/** RFC 6238 Appendix B (SHA-1), last 6 of the 8 published digits. */
	@ParameterizedTest
	@CsvSource({ "59, 287082", "1111111109, 081804", "1111111111, 050471", "1234567890, 005924",
			"2000000000, 279037", "20000000000, 353130" })
	void matchesTheRfc6238TestVectors(long epochSecond, String code) {
		assertThat(Totp.codeAt(SECRET, Instant.ofEpochSecond(epochSecond))).isEqualTo(code);
	}

	@Test
	void acceptsTheCurrentStepAndOneStepOfDrift() {
		Instant now = Instant.ofEpochSecond(1111111111);
		long step = Totp.stepOf(now);

		assertThat(Totp.match(SECRET, Totp.codeAt(SECRET, now), now, null)).hasValue(step);
		assertThat(Totp.match(SECRET, Totp.codeAt(SECRET, now.minusSeconds(30)), now, null)).hasValue(step - 1);
		assertThat(Totp.match(SECRET, Totp.codeAt(SECRET, now.plusSeconds(30)), now, null)).hasValue(step + 1);
		assertThat(Totp.match(SECRET, Totp.codeAt(SECRET, now.minusSeconds(60)), now, null)).isEmpty();
	}

	@Test
	void neverAcceptsAStepUpToTheLastUsedOne() {
		Instant now = Instant.ofEpochSecond(1111111111);
		String code = Totp.codeAt(SECRET, now);

		assertThat(Totp.match(SECRET, code, now, Totp.stepStart(Totp.stepOf(now)))).isEmpty();
		assertThat(Totp.match(SECRET, code, now, Totp.stepStart(Totp.stepOf(now) - 1))).isPresent();
	}

	@Test
	void rejectsMalformedCodes() {
		Instant now = Instant.ofEpochSecond(59);

		assertThat(Totp.match(SECRET, "28708", now, null)).isEmpty();
		assertThat(Totp.match(SECRET, "2870821", now, null)).isEmpty();
		assertThat(Totp.match(SECRET, "28708a", now, null)).isEmpty();
	}

	@Test
	void secretsAre160BitsAndRoundTripThroughBase32() {
		byte[] secret = Totp.newSecret();

		assertThat(secret).hasSize(20);
		assertThat(Totp.base32(secret)).hasSize(32).matches("[A-Z2-7]+");
		assertThat(Totp.base32(SECRET)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
	}

}
