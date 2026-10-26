package com.otr.portal.proxy.rewriters;

import com.otr.portal.proxy.rewriters.bytes.ByteContentRewriter;
import com.otr.portal.proxy.rewriters.bytes.ByteRewriteChain;
import com.otr.portal.proxy.rewriters.bytes.ByteRewriteContext;
import com.otr.portal.proxy.rewriters.bytes.ByteTextSyntax;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.MediaType;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Перезапись тела ответа цепочкой {@link ContentRewriter}.
 * <p>
 * Если у всех переписчиков есть {@link ByteContentRewriter} и кодировка разбирается по байтам,
 * тело переписывается без {@link String} и без копирования неизменённых участков.
 * Иначе (или при включённом legacy-режиме) - прежним способом через строку в явной кодировке.
 */
@Slf4j
public class ResponseBodyRewriter {
    private static final String BYTE_MODE = "rewrite:bytes";
    private static final String LEGACY_MODE = "rewrite:legacy";

    private final CompoundContentRewriter legacyChain;
    @Nullable
    private final ByteRewriteChain byteChain;
    private final BooleanSupplier legacyMode;

    public ResponseBodyRewriter(List<ContentRewriter> rewriters, BooleanSupplier legacyMode) {
        this.legacyChain = new CompoundContentRewriter(rewriters.toArray(new ContentRewriter[0]));
        this.byteChain = createByteChain(rewriters);
        this.legacyMode = legacyMode;
    }

    public String describeMode(MediaType contentType) {
        return resolveByteSyntax(contentType) != null
                ? BYTE_MODE
                : LEGACY_MODE;
    }

    /**
     * [Шаг 6.3] Забирает владение телом запроса.
     *
     * @return итоговое тело, которым владеет вызывающий
     */
    public ByteBuf rewrite(BodyRewriteRequest request) {
        // Байтовый путь возможен, только если его поддерживают все переписчики и кодировка тела.
        ByteTextSyntax syntax = resolveByteSyntax(request.getContentType());
        if (syntax == null) {
            return rewriteLegacy(request);
        }
        var rewriteContext = ByteRewriteContext.builder()
                .source(request.getBody())
                .contentType(request.getContentType())
                .syntax(syntax)
                .backend(request.getBackend())
                .request(request.getRequest())
                .allocator(request.getAllocator())
                .build();

        return byteChain.rewrite(rewriteContext);
    }

    @Nullable
    private ByteTextSyntax resolveByteSyntax(MediaType contentType) {
        if (byteChain == null || legacyMode.getAsBoolean()) {
            return null;
        }
        return ByteTextSyntax.forCharset(resolveCharset(contentType));
    }

    /**
     * [Шаг 6.3, legacy] Прежний путь через {@link String}: несколько полных копий тела в heap. Используется при legacy-флаге,
     * для кодировок, которые нельзя разбирать по байтам (например UTF-16), и для переписчиков без байтовой реализации.
     */
    private ByteBuf rewriteLegacy(BodyRewriteRequest request) {
        Charset charset = resolveCharset(request.getContentType());
        ByteBuf body = request.getBody();
        String text;
        try {
            text = body.toString(charset);
        } finally {
            body.release();
        }
        String rewritten = legacyChain.rewrite(request.getContentType(), text, request.getBackend(), request.getRequest());
        return Unpooled.wrappedBuffer(rewritten.getBytes(charset));
    }

    /**
     * Кодировка из {@code Content-Type}; если её нет или она неизвестна JVM - UTF-8.
     */
    private static Charset resolveCharset(MediaType contentType) {
        try {
            Charset charset = contentType.getCharset();
            return charset != null
                    ? charset
                    : StandardCharsets.UTF_8;
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * Цепочка переписчиков применяется либо целиком по байтам, либо целиком через строку:
     * смешивать нельзя, иначе тело пришлось бы декодировать ради одного шага.
     */
    @Nullable
    private static ByteRewriteChain createByteChain(List<ContentRewriter> rewriters) {
        List<String> withoutByteSupport = rewriters.stream()
                .filter(rewriter -> !(rewriter instanceof ByteContentRewriter))
                .map(rewriter -> rewriter.getClass().getName())
                .collect(Collectors.toList());
        if (!withoutByteSupport.isEmpty()) {
            log.warn("Перезапись контента будет выполняться через String: нет реализации ByteContentRewriter у {}", withoutByteSupport);
            return null;
        }
        return new ByteRewriteChain(rewriters.stream()
                .map(ByteContentRewriter.class::cast)
                .collect(Collectors.toList()));
    }
}
