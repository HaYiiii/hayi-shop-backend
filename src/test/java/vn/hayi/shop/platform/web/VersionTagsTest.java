package vn.hayi.shop.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;

/** {@code ETag}/{@code If-Match} as {@code "v{version}"} (API README §8, Decision O4). */
class VersionTagsTest {

	@Test
	void tagRoundTripsToExpectedVersion() {
		assertThat(VersionTags.of(7)).isEqualTo("\"v7\"");
		assertThat(VersionTags.require("\"v7\"")).isEqualTo(new ExpectedVersion(7));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "  " })
	void missingIfMatchIsPreconditionRequired(String ifMatch) {
		assertThatThrownBy(() -> VersionTags.require(ifMatch)).isInstanceOfSatisfying(DomainException.class,
				ex -> assertThat(ex.code()).isEqualTo(CommonErrorCode.PRECONDITION_REQUIRED));
	}

	@ParameterizedTest
	@ValueSource(strings = { "*", "W/\"v1\"", "v1", "\"1\"", "\"v1\", \"v2\"", "\"vx\"" })
	void anythingButOneStrongVersionTagIsAConflict(String ifMatch) {
		assertThatThrownBy(() -> VersionTags.require(ifMatch)).isInstanceOfSatisfying(DomainException.class,
				ex -> assertThat(ex.code()).isEqualTo(CommonErrorCode.VERSION_CONFLICT));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "  " })
	void optionalIfMatchMayBeMissing(String ifMatch) {
		assertThat(VersionTags.optional(ifMatch)).isNull();
	}

	@Test
	void optionalIfMatchStillMustBeAVersionTag() {
		assertThat(VersionTags.optional("\"v2\"")).isEqualTo(new ExpectedVersion(2));
		assertThatThrownBy(() -> VersionTags.optional("*")).isInstanceOfSatisfying(DomainException.class,
				ex -> assertThat(ex.code()).isEqualTo(CommonErrorCode.VERSION_CONFLICT));
	}

	@Test
	void staleVersionIsAConflict() {
		assertThatThrownBy(() -> new ExpectedVersion(1).verify(2)).isInstanceOfSatisfying(DomainException.class,
				ex -> assertThat(ex.code()).isEqualTo(CommonErrorCode.VERSION_CONFLICT));
	}

}
