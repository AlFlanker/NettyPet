package com.otr.portal.proxy.rewriters;

import com.otr.portal.hosts.Backend;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;

/**
 * @author pobedenniy.alexey
 * @since 24.11.17
 */
public class CompoundContentRewriter implements ContentRewriter {
    private ContentRewriter[] rewriters;

    public CompoundContentRewriter(ContentRewriter... rewriters) {
        this.rewriters = rewriters;
    }

    @Override
    public String rewrite(MediaType contentType, String body, Backend backend, HttpServletRequest req) {
        for (ContentRewriter rewriter : rewriters) {
            body = rewriter.rewrite(contentType, body, backend, req);
        }
        return body;
    }
}
