package com.otr.portal.proxy.rewriters.bytes;

import com.otr.portal.hosts.Backend;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.Value;
import lombok.With;
import org.springframework.http.MediaType;

/**
 * Параметры одного шага байтовой перезаписи тела ответа.
 */
@Value
@Builder
public class ByteRewriteContext {
    @With
    ByteBuf source;
    MediaType contentType;
    ByteTextSyntax syntax;
    Backend backend;
    HttpServletRequest request;
    ByteBufAllocator allocator;
}
