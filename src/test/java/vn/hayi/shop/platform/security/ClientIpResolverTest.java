package vn.hayi.shop.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The browser reaches the API through the ingress and the web's same-origin {@code /api/*} proxy (Decision AU1, AU3):
 * browser 203.0.113.7 → ingress 10.0.0.2 → web proxy 10.0.0.5 → API.
 */
class ClientIpResolverTest {

	static final String INGRESS = "10.0.0.2";

	static final String WEB_PROXY = "10.0.0.5";

	@Test
	void theBrowserIsFoundBehindTheIngressAndTheWebProxy() {
		ClientIpResolver resolver = new ClientIpResolver(List.of(INGRESS, WEB_PROXY));

		assertThat(resolver.resolve(viaWebProxy("203.0.113.7, " + INGRESS))).isEqualTo("203.0.113.7");
	}

	@Test
	void aForwardedForSentByTheBrowserItselfIsIgnored() {
		ClientIpResolver resolver = new ClientIpResolver(List.of(INGRESS, WEB_PROXY));

		assertThat(resolver.resolve(viaWebProxy("1.2.3.4, 203.0.113.7, " + INGRESS))).isEqualTo("203.0.113.7");
	}

	@Test
	void everyClientLooksLikeTheWebProxyWhenItIsNotTrusted() {
		ClientIpResolver resolver = new ClientIpResolver(List.of(INGRESS));

		assertThat(resolver.resolve(viaWebProxy("203.0.113.7, " + INGRESS))).isEqualTo(WEB_PROXY);
	}

	private static MockHttpServletRequest viaWebProxy(String forwardedFor) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(WEB_PROXY);
		request.addHeader("X-Forwarded-For", forwardedFor);
		return request;
	}

}
