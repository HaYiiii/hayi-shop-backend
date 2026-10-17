package vn.hayi.shop.identity.domain;

import java.time.Duration;

/**
 * Temporary lock after repeated failures (API 01-security §6.3): {@code maxFailures} failed logins with no gap
 * longer than {@code failureWindow} lock the account for {@code lockDuration}.
 */
public record LoginLockPolicy(int maxFailures, Duration failureWindow, Duration lockDuration) {

}
