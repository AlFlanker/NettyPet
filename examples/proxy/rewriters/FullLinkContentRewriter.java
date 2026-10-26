package com.otr.portal.proxy.rewriters;

import com.otr.portal.hosts.Backend;
import jakarta.servlet.http.HttpServletRequest;
import com.otr.portal.proxy.rewriters.bytes.ByteContentRewriter;
import com.otr.portal.proxy.rewriters.bytes.ByteRewriteContext;
import com.otr.portal.proxy.rewriters.bytes.LiteralByteRewriter;
import io.netty.buffer.ByteBuf;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * @author pobedenniy.alexey
 * @since 24.11.17
 */
@Component
public class FullLinkContentRewriter implements ContentRewriter, ByteContentRewriter {
    @Override
    public String rewrite(MediaType contentType, String body, Backend backend, HttpServletRequest req) {
        return StringUtils.replace(body, backend.fullBackendUrl("/"), backend.fullFrontendUrl("/"));
    }

    /**
     * Та же замена абсолютного адреса бэкенда на адрес портала, но по байтам тела без {@link String}.
     */
    @Override
    public ByteBuf rewrite(ByteRewriteContext context) {
        Backend backend = context.getBackend();
        return LiteralByteRewriter.replaceLiteral(context, backend.fullBackendUrl("/"), backend.fullFrontendUrl("/"));
    }
}
