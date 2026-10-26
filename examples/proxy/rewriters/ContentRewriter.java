package com.otr.portal.proxy.rewriters;

import com.otr.portal.hosts.Backend;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;

/**
 * @author pobedenniy.alexey
 * @since 24.11.17
 */
public interface ContentRewriter {
    String rewrite(MediaType contentType, String body, Backend backend, HttpServletRequest req);
}
