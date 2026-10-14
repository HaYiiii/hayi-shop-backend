package vn.hayi.shop.platform.web;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

import vn.hayi.shop.platform.observability.TraceIds;
import vn.hayi.shop.shared.error.ErrorCode;
import vn.hayi.shop.shared.error.ErrorKind;

/**
 * Builds RFC 9457 Problem Details in the HaYi shape (API README §6): stable {@code code}, {@code traceId},
 * {@code title}/{@code detail} localized from {@code messages*.properties} by {@code Accept-Language}.
 */
@Component
class ProblemFactory {

	static final String TYPE_PREFIX = "https://docs.hayi.vn/errors/";

	private static final Map<ErrorKind, HttpStatus> STATUS_BY_KIND = Map.of(
			ErrorKind.VALIDATION, HttpStatus.BAD_REQUEST,
			ErrorKind.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED,
			ErrorKind.FORBIDDEN, HttpStatus.FORBIDDEN,
			ErrorKind.NOT_FOUND, HttpStatus.NOT_FOUND,
			ErrorKind.CONFLICT, HttpStatus.CONFLICT,
			ErrorKind.BUSINESS_RULE, HttpStatus.UNPROCESSABLE_CONTENT,
			ErrorKind.INTERNAL, HttpStatus.INTERNAL_SERVER_ERROR);

	private final MessageSource messages;

	private final LocaleResolver localeResolver;

	ProblemFactory(MessageSource messages, LocaleResolver localeResolver) {
		this.messages = messages;
		this.localeResolver = localeResolver;
	}

	HttpStatus statusOf(ErrorCode code) {
		return STATUS_BY_KIND.get(code.kind());
	}

	ProblemDetail create(ErrorCode code, HttpServletRequest request, Object... detailArgs) {
		return create(statusOf(code), code.name(), request, detailArgs);
	}

	/** Also used for framework errors whose code is derived from the status rather than an {@link ErrorCode}. */
	ProblemDetail create(HttpStatus status, String code, HttpServletRequest request, Object... detailArgs) {
		Locale locale = this.localeResolver.resolveLocale(request);
		ProblemDetail problem = ProblemDetail.forStatus(status);
		problem.setType(URI.create(TYPE_PREFIX + code));
		problem.setTitle(this.messages.getMessage("error." + code + ".title", null, status.getReasonPhrase(), locale));
		problem.setDetail(this.messages.getMessage("error." + code + ".detail", detailArgs, null, locale));
		problem.setProperty("code", code);
		decorate(problem);
		return problem;
	}

	/** Adds the request's trace id; every problem carries it (API README §9). */
	void decorate(ProblemDetail problem) {
		problem.setProperty("traceId", TraceIds.current());
	}

}
