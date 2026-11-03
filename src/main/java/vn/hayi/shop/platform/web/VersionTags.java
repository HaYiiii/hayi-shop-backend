package vn.hayi.shop.platform.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;

/**
 * {@code ETag}/{@code If-Match} for every versioned resource (API README §8): the tag is {@code "v{version}"}.
 * {@code PATCH}/{@code PUT} on config and master data call {@link #require(String)}, then let the use case verify
 * the {@link ExpectedVersion} against the aggregate.
 */
public final class VersionTags {

	private static final Pattern TAG = Pattern.compile("\"v(\\d{1,18})\"");

	private VersionTags() {
	}

	public static String of(long version) {
		return "\"v" + version + "\"";
	}

	/**
	 * The version an edit is based on.
	 * @throws DomainException {@code PRECONDITION_REQUIRED} when {@code ifMatch} is missing; {@code VERSION_CONFLICT}
	 * when it is anything but one strong {@code "v{n}"} tag — a weak tag, {@code *} or a list cannot match a version
	 */
	public static ExpectedVersion require(String ifMatch) {
		if (ifMatch == null || ifMatch.isBlank()) {
			throw new DomainException(CommonErrorCode.PRECONDITION_REQUIRED, "If-Match missing");
		}
		Matcher matcher = TAG.matcher(ifMatch.strip());
		if (!matcher.matches()) {
			throw new DomainException(CommonErrorCode.VERSION_CONFLICT, "If-Match is not a version tag: " + ifMatch);
		}
		return new ExpectedVersion(Long.parseLong(matcher.group(1)));
	}

}
