package vn.hayi.shop.identity.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import vn.hayi.shop.shared.error.DomainException;

/** Internal user passwords: 12–128 characters, not common, no composition rules (Decision K4). */
class PasswordPolicyTest {

	@ParameterizedTest
	@ValueSource(strings = { "twelve chars", "càphêsữađáĐà", "all lowercase words are fine here" })
	void acceptsLongEnoughUncommonPasswords(String password) {
		assertThatCode(() -> PasswordPolicy.check(password)).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "eleven char", "Password1234", "QWERTY123456", "matkhau12345" })
	void rejectsShortOrCommonPasswords(String password) {
		assertThatThrownBy(() -> PasswordPolicy.check(password)).isInstanceOf(DomainException.class)
			.extracting(ex -> ((DomainException) ex).code())
			.isEqualTo(IdentityErrorCode.AUTH_PASSWORD_POLICY_VIOLATION);
	}

	@ParameterizedTest
	@ValueSource(ints = { 128, 129 })
	void limitsLengthTo128Characters(int length) {
		String password = "a".repeat(length);
		if (length <= 128) {
			assertThatCode(() -> PasswordPolicy.check(password)).doesNotThrowAnyException();
		}
		else {
			assertThatThrownBy(() -> PasswordPolicy.check(password)).isInstanceOf(DomainException.class);
		}
	}

}
