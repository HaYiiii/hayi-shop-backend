package vn.hayi.shop.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import vn.hayi.shop.customer.api.CustomerErrorCode;
import vn.hayi.shop.customer.api.CustomerProfiles;
import vn.hayi.shop.identity.domain.IdentifierType;
import vn.hayi.shop.identity.domain.IdentityErrorCode;
import vn.hayi.shop.identity.domain.LoginIdentifier;
import vn.hayi.shop.identity.domain.LoginIdentifierRepository;
import vn.hayi.shop.identity.domain.PasswordPolicy;
import vn.hayi.shop.identity.domain.User;
import vn.hayi.shop.identity.domain.UserCredential;
import vn.hayi.shop.identity.domain.UserCredentialRepository;
import vn.hayi.shop.identity.domain.UserRepository;
import vn.hayi.shop.identity.domain.VerificationChallenge;
import vn.hayi.shop.identity.domain.VerificationChallengeRepository;
import vn.hayi.shop.identity.domain.VerificationPurpose;
import vn.hayi.shop.platform.security.Tokens;
import vn.hayi.shop.shared.error.DomainException;

/**
 * Customer registration (Identity §2.11, Decision AN4): one transaction creates the User, its verified phone and
 * credential when the phone is new, adds the CustomerProfile through {@code customer.api} and consumes the OTP proof.
 * There is never a registered Customer without a profile. No session is opened: the Customer logs in next.
 */
@Service
public class CustomerRegistrationService {

	private final Tokens tokens;

	private final VerificationChallengeRepository challenges;

	private final LoginIdentifierRepository identifiers;

	private final UserRepository users;

	private final UserCredentialRepository credentials;

	private final CustomerProfiles profiles;

	private final PasswordEncoder passwordEncoder;

	private final TransactionTemplate transactions;

	private final Clock clock;

	CustomerRegistrationService(Tokens tokens, VerificationChallengeRepository challenges,
			LoginIdentifierRepository identifiers, UserRepository users, UserCredentialRepository credentials,
			CustomerProfiles profiles, PasswordEncoder passwordEncoder, TransactionTemplate transactions, Clock clock) {
		this.tokens = tokens;
		this.challenges = challenges;
		this.identifiers = identifiers;
		this.users = users;
		this.credentials = credentials;
		this.profiles = profiles;
		this.passwordEncoder = passwordEncoder;
		this.transactions = transactions;
		this.clock = clock;
	}

	/** {@code password} is for a new phone, {@code currentPassword} for a phone that already has a User. */
	public record Registration(String verificationToken, String displayName, String password,
			String currentPassword) {
	}

	/**
	 * @return the new {@code customerId}
	 * @throws DomainException {@code VERIFICATION_TOKEN_INVALID}, {@code AUTH_PASSWORD_POLICY_VIOLATION},
	 * {@code AUTH_CURRENT_PASSWORD_REQUIRED}, {@code AUTH_INVALID_CREDENTIALS}, {@code CUSTOMER_ALREADY_REGISTERED}
	 */
	public UUID register(Registration request) {
		Outcome outcome = this.transactions.execute(status -> {
			try {
				return new Outcome.Registered(registerInTransaction(request));
			}
			catch (WrongCurrentPassword ex) {
				// The proof is spent even though the request fails, so one OTP buys one password guess.
				return new Outcome.Refused(IdentityErrorCode.AUTH_INVALID_CREDENTIALS);
			}
		});
		return switch (outcome) {
			case Outcome.Registered registered -> registered.customerId();
			case Outcome.Refused refused -> throw new DomainException(refused.code(), "Registration refused");
		};
	}

	private sealed interface Outcome {

		record Registered(UUID customerId) implements Outcome {
		}

		record Refused(IdentityErrorCode code) implements Outcome {
		}

	}

	private static final class WrongCurrentPassword extends RuntimeException {

		private WrongCurrentPassword() {
			super(null, null, false, false);
		}

	}

	private UUID registerInTransaction(Registration request) {
		Instant now = this.clock.instant();
		VerificationChallenge proof = this.tokens.verifyVerificationToken(request.verificationToken())
			.flatMap(this.challenges::findByIdForUpdate)
			.filter(challenge -> challenge.getPurpose() == VerificationPurpose.REGISTRATION)
			.filter(VerificationChallenge::isVerified)
			.orElseThrow(() -> new DomainException(IdentityErrorCode.VERIFICATION_TOKEN_INVALID,
					"Invalid, expired or used verification token"));
		String phone = proof.getTarget();
		Optional<LoginIdentifier> existing = this.identifiers.findByTypeAndNormalizedValue(IdentifierType.PHONE, phone);
		UUID userId;
		if (existing.isEmpty()) {
			if (request.password() == null) {
				throw PasswordPolicy.violation();
			}
			PasswordPolicy.checkCustomer(request.password());
			User user = this.users.save(User.create(now));
			userId = user.getId();
			this.identifiers.save(LoginIdentifier.verifiedPhone(userId, phone, now));
			this.credentials.save(UserCredential.chosen(userId, this.passwordEncoder.encode(request.password()), now));
		}
		else {
			userId = existing.get().getUserId();
			if (this.profiles.existsForUser(userId)) {
				throw alreadyRegistered();
			}
			if (request.currentPassword() == null) {
				throw new DomainException(IdentityErrorCode.AUTH_CURRENT_PASSWORD_REQUIRED,
						"This phone already has an account: confirm its password");
			}
			User user = this.users.findByIdForUpdate(userId).orElseThrow();
			boolean matches = this.credentials.findByIdForUpdate(userId)
				.map(credential -> this.passwordEncoder.matches(request.currentPassword(), credential.getPasswordHash()))
				.orElse(false);
			if (!matches || !user.isActive()) {
				proof.consumeVerified(now);
				throw new WrongCurrentPassword();
			}
		}
		UUID customerId = this.profiles.create(userId, request.displayName());
		proof.consumeVerified(now);
		return customerId;
	}

	private static DomainException alreadyRegistered() {
		// Reported with the Customer module's code so both paths answer the same way (Decision AN4).
		return new DomainException(CustomerErrorCode.CUSTOMER_ALREADY_REGISTERED,
				"This phone is already a Customer");
	}

}
