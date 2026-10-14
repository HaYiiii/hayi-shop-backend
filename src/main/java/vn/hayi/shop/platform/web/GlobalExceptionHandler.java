package vn.hayi.shop.platform.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.ErrorCode;

/**
 * Single place that turns exceptions into Problem Details (API README §6). Also serves errors raised by the
 * security filter chain, which delegates here through the {@code HandlerExceptionResolver}.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private final ProblemFactory problems;

	GlobalExceptionHandler(ProblemFactory problems) {
		this.problems = problems;
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<ProblemDetail> handleUnauthenticated(AuthenticationException ex, HttpServletRequest request) {
		return respond(CommonErrorCode.AUTH_REQUIRED, request);
	}

	private ResponseEntity<ProblemDetail> respond(ErrorCode code, HttpServletRequest request, Object... detailArgs) {
		return ResponseEntity.status(this.problems.statusOf(code)).body(this.problems.create(code, request, detailArgs));
	}

}
