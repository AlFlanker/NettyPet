package com.otr.portal.proxy.headers;

import com.otr.portal.core.model.http.Header;
import com.otr.portal.hosts.Backend;
import jakarta.servlet.http.HttpServletRequest;

import java.util.stream.Stream;

import static com.otr.portal.HeaderNames.APP_NAME_HEADER;

/**
 * @author pobedenniy.alexey
 * @since 22.02.19
 */
public class ForApplicationHeaderInterceptor implements HeaderInterceptor {
	private final String appName;

	public ForApplicationHeaderInterceptor(String appName) {
		this.appName = appName;
	}

	@Override
	public Stream<Header> headersForBackend(HttpServletRequest req, Backend backend) {
		return Stream.of(new Header(APP_NAME_HEADER, appName));
	}
}
