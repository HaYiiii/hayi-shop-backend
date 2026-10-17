package vn.hayi.shop.identity.application;

import vn.hayi.shop.shared.error.ErrorCode;

/** Outcome of a refresh; a rejection still commits its side effects (session revoke, security events). */
public sealed interface RefreshResult {

	record Rotated(SessionTokens tokens) implements RefreshResult {
	}

	record Rejected(ErrorCode code) implements RefreshResult {
	}

}
