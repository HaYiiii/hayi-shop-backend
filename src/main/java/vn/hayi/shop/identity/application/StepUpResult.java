package vn.hayi.shop.identity.application;

import vn.hayi.shop.shared.error.ErrorCode;

/** Outcome of a step-up; a value, so failure counting and a revoke commit even though the step-up is refused. */
public sealed interface StepUpResult {

	record Verified() implements StepUpResult {
	}

	record Rejected(ErrorCode code) implements StepUpResult {
	}

}
