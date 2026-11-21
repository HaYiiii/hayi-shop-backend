package vn.hayi.shop.identity.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import vn.hayi.shop.identity.domain.IdentityErrorCode;
import vn.hayi.shop.identity.domain.PhoneNumbers;
import vn.hayi.shop.identity.domain.VerificationChallenge;
import vn.hayi.shop.identity.domain.VerificationChallengeRepository;
import vn.hayi.shop.identity.domain.VerificationPurpose;
import vn.hayi.shop.identity.domain.VerificationStatus;
import vn.hayi.shop.identity.infrastructure.SecurityEventLog;
import vn.hayi.shop.identity.infrastructure.SecurityEventType;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.Tokens;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.error.ThrottledException;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * Sends and checks phone OTPs (Identity §2.3, API 01-security §6.1, Decision AN1–AN3, AN5–AN6). A request always
 * looks the same whether or not the phone has an account.
 */
@Service
public class OtpService {

	private static final SecureRandom RANDOM = new SecureRandom();

	/** Lifetime of the proof returned by verify (Decision AN3). */
	static final Duration TOKEN_TTL = Duration.ofMinutes(10);

	private final VerificationChallengeRepository challenges;

	private final OtpSender sender;

	private final SecurityEventLog securityEvents;

	private final Tokens tokens;

	private final IdentityProperties.Otp settings;

	private final TransactionTemplate transactions;

	private final Clock clock;

	OtpService(VerificationChallengeRepository challenges, OtpSender sender, SecurityEventLog securityEvents,
			Tokens tokens, IdentityProperties properties, TransactionTemplate transactions, Clock clock) {
		this.challenges = challenges;
		this.sender = sender;
		this.securityEvents = securityEvents;
		this.tokens = tokens;
		this.settings = properties.otp();
		this.transactions = transactions;
		this.clock = clock;
	}

	public record Sent(UUID challengeId, Instant expiresAt, long resendAfterSeconds) {
	}

	/** The E.164 form of a Vietnamese phone, or empty (Decision AN2); the caller reports the field. */
	public static Optional<String> normalizePhone(String input) {
		return PhoneNumbers.normalize(input);
	}

	/**
	 * Sends a code for {@code purpose} as named in the API.
	 * @see #request(String, VerificationPurpose, ClientInfo)
	 */
	public Sent request(String phone, String purpose, ClientInfo client) {
		return request(phone, VerificationPurpose.valueOf(purpose), client);
	}

	public record Verified(String verificationToken, Instant expiresAt) {
	}

	/**
	 * Sends a new code to {@code phone} (E.164), ending its open ones (AN1).
	 * @throws ThrottledException {@code RATE_LIMITED} over 1 / resend interval / target, 5 / hour / target or
	 * 20 / hour / IP (AN5); checked outside the transaction so the {@code RATE_LIMIT_EXCEEDED} event is kept
	 */
	public Sent request(String phone, VerificationPurpose purpose, ClientInfo client) {
		Instant now = this.clock.instant();
		throttle(phone, purpose, client, now);
		UUID id = UuidV7.generate();
		String code = String.format("%06d", RANDOM.nextInt(1_000_000));
		this.transactions.executeWithoutResult(status -> {
			this.challenges.expireOpenForTarget(phone, purpose, VerificationStatus.PENDING,
					VerificationStatus.EXPIRED);
			this.challenges.save(VerificationChallenge.otp(id, purpose, phone, VerificationChallenge.hashOtp(id, code),
					now, this.settings.ttl(), this.settings.maxAttempts()));
			this.securityEvents.record(SecurityEventType.OTP_REQUESTED, null, null, null, client,
					metadata(purpose, phone));
		});
		this.sender.send(phone, code, purpose);
		return new Sent(id, now.plus(this.settings.ttl()), this.settings.resendInterval().toSeconds());
	}

	/**
	 * Checks a code. A wrong code is counted and its event written even though the request fails, so the attempt
	 * limit holds (AN3).
	 * @throws DomainException {@code OTP_CODE_INVALID}, {@code OTP_CHALLENGE_LOCKED} on the last allowed attempt,
	 * {@code OTP_CHALLENGE_INVALID} when unknown, expired, already used or locked
	 */
	public Verified verify(UUID challengeId, String code, ClientInfo client) {
		Outcome outcome = this.transactions.execute(status -> check(challengeId, code, client));
		return switch (outcome) {
			case Outcome.Ok ok -> ok.verified();
			case Outcome.Refused refused -> throw new DomainException(refused.code(), "OTP refused: " + refused.code());
		};
	}

	private sealed interface Outcome {

		record Ok(Verified verified) implements Outcome {
		}

		record Refused(IdentityErrorCode code) implements Outcome {
		}

	}

	private Outcome check(UUID challengeId, String code, ClientInfo client) {
		Instant now = this.clock.instant();
		VerificationChallenge challenge = this.challenges.findByIdForUpdate(challengeId)
			.filter(found -> found.getPurpose() != VerificationPurpose.MFA_LOGIN)
			.orElse(null);
		if (challenge == null || !challenge.isOpen(now)) {
			return new Outcome.Refused(IdentityErrorCode.OTP_CHALLENGE_INVALID);
		}
		Map<String, Object> metadata = metadata(challenge.getPurpose(), challenge.getTarget());
		if (!challenge.matches(code)) {
			boolean locked = challenge.registerWrongCode();
			this.securityEvents.record(SecurityEventType.OTP_FAILED, null, null, null, client, metadata);
			return new Outcome.Refused(
					locked ? IdentityErrorCode.OTP_CHALLENGE_LOCKED : IdentityErrorCode.OTP_CODE_INVALID);
		}
		challenge.markVerified(now);
		this.securityEvents.record(SecurityEventType.OTP_VERIFIED, null, null, null, client, metadata);
		Instant expiresAt = now.plus(TOKEN_TTL);
		return new Outcome.Ok(new Verified(this.tokens.issueVerificationToken(challengeId, expiresAt), expiresAt));
	}

	private void throttle(String phone, VerificationPurpose purpose, ClientInfo client, Instant now) {
		List<Instant> lastHour = this.challenges.findSentSince(phone, purpose, now.minus(Duration.ofHours(1)));
		Duration wait = Duration.ZERO;
		if (!lastHour.isEmpty()) {
			Instant last = lastHour.getLast();
			Duration sinceLast = Duration.between(last, now);
			if (sinceLast.compareTo(this.settings.resendInterval()) < 0) {
				wait = this.settings.resendInterval().minus(sinceLast);
			}
			if (lastHour.size() >= this.settings.perTargetPerHour()) {
				wait = max(wait, Duration.between(now,
						lastHour.get(lastHour.size() - this.settings.perTargetPerHour()).plus(Duration.ofHours(1))));
			}
		}
		if (client.ipAddress() != null) {
			List<Instant> fromIp = this.securityEvents.occurredFromIpSince(SecurityEventType.OTP_REQUESTED,
					client.ipAddress(), now.minus(Duration.ofHours(1)));
			if (fromIp.size() >= this.settings.perIpPerHour()) {
				wait = max(wait, Duration.between(now,
						fromIp.get(fromIp.size() - this.settings.perIpPerHour()).plus(Duration.ofHours(1))));
			}
		}
		if (wait.isPositive()) {
			this.securityEvents.rateLimitExceeded(client,
					Map.of("bucket", "OTP", "purpose", purpose.name(), "target", PhoneNumbers.mask(phone)));
			throw new ThrottledException(CommonErrorCode.RATE_LIMITED, "OTP rate limit", wait);
		}
	}

	private static Duration max(Duration a, Duration b) {
		return (a.compareTo(b) >= 0) ? a : b;
	}

	private static Map<String, Object> metadata(VerificationPurpose purpose, String target) {
		return Map.of("purpose", purpose.name(), "target", PhoneNumbers.mask(target));
	}

}
