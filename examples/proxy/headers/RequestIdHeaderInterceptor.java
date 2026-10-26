package com.otr.portal.proxy.headers;

import com.otr.portal.core.model.http.Header;
import com.otr.portal.hosts.Backend;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.stream.Stream;

import static com.otr.portal.HeaderNames.REQUEST_ID_HEADER;
import static com.otr.portal.RequestAttributeNames.REQUEST_ATTRIBUTE_REQUEST_ID;

/**
 * @author pobedenniy.alexey
 * @since 05.02.19
 */
@Component
public class RequestIdHeaderInterceptor implements HeaderInterceptor {
    @Override
    public Stream<Header> headersForBackend(HttpServletRequest req, Backend backend) {
        return Stream.of(new Header(REQUEST_ID_HEADER, String.valueOf(req.getAttribute(REQUEST_ATTRIBUTE_REQUEST_ID))));
    }
}
