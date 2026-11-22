package vn.hayi.shop.platform.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Who is calling, as recorded on sessions, security events and audit ({@code ip_address VARCHAR(45)},
 * {@code user_agent VARCHAR(512)}, ERD 01/16). Values are truncated to the column sizes.
 */
public record ClientInfo(String ipAddress, String userAgent) {

	/** Request attribute holding the IP resolved through the trusted proxies, set by {@link ClientIpFilter}. */
	static final String IP_ATTRIBUTE = ClientInfo.class.getName() + ".ip";

	private static final ClientInfo NONE = new ClientInfo(null, null);

	public ClientInfo {
		ipAddress = truncate(ipAddress, 45);
		userAgent = truncate(userAgent, 512);
	}

	/**
	 * The caller of this request: the IP resolved through the trusted proxies (Decision AU3), or the peer address
	 * when the request did not pass {@link ClientIpFilter}.
	 */
	public static ClientInfo from(HttpServletRequest request) {
		Object resolved = request.getAttribute(IP_ATTRIBUTE);
		String ip = (resolved instanceof String value) ? value : request.getRemoteAddr();
		return new ClientInfo(ip, request.getHeader("User-Agent"));
	}

	public static ClientInfo none() {
		return NONE;
	}

	private static String truncate(String value, int max) {
		return (value != null && value.length() > max) ? value.substring(0, max) : value;
	}

}
