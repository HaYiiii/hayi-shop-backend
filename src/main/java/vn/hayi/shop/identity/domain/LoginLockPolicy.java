package vn.hayi.shop.identity.domain;

import java.time.Duration;

/**
 * Temporary lock after repeated failures (API 01-security §6.3, Decision K2): {@code maxFailures} failed logins
 * within the latest {@code failureWindow} lock the account for {@code lockDuration}.
 */
public record LoginLockPolicy(int maxFailures, Duration failureWindow, Duration lockDuration) {

}
