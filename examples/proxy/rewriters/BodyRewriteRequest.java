package com.otr.portal.proxy.rewriters;

import com.otr.portal.hosts.Backend;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.Value;
import org.springframework.http.MediaType;

/**
 * Тело ответа бэкенда для перезаписи; {@link ResponseBodyRewriter} забирает владение {@link #body}.
 */
@Value
@Builder
public class BodyRewriteRequest {
    ByteBuf body;
    MediaType contentType;
    Backend backend;
    HttpServletRequest request;
    ByteBufAllocator allocator;
}
