package vn.hayi.shop.platform.security;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** When a session last stepped up (Identity §2.4); implemented by the identity module, which owns sessions. */
public interface StepUpChecker {

	Optional<Instant> stepUpVerifiedAt(UUID sessionId);

}
