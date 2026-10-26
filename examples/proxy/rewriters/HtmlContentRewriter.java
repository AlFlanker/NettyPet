package com.otr.portal.proxy.rewriters;

import com.otr.portal.hosts.Backend;
import com.otr.portal.proxy.rewriters.bytes.ByteContentRewriter;
import com.otr.portal.proxy.rewriters.bytes.ByteRewriteContext;
import com.otr.portal.proxy.rewriters.bytes.HtmlLinkByteRewriter;
import io.netty.buffer.ByteBuf;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/**
 * @author pobedenniy.alexey
 * @since 24.11.17
 */
@Component
public class HtmlContentRewriter implements ContentRewriter, ByteContentRewriter {
    private final HtmlLinkByteRewriter byteRewriter = new HtmlLinkByteRewriter();
    private final Pattern baseTagPattern;
    private final Pattern hrefPattern;
    private final Pattern formActionPattern;
    private final Pattern hrefPatternRel;

    public HtmlContentRewriter() {
        // Тег base используется для указания базового адреса страницы.
        baseTagPattern = Pattern.compile("<base\\s+href=.+>.*");
        //replace href="/1.png",
        hrefPattern = Pattern.compile("href=[\"|'](/.+?)[\"|']");
        //replace  href="../1.png", href="./1.png"
        hrefPatternRel = Pattern.compile("href=[\"|']((\\.\\./|\\./).+?)[\"|']");
        //replace <form action="/url">
        formActionPattern = Pattern.compile("<form(.+?)action=\"([^\"]+?)\"(.*?)>", Pattern.DOTALL);
    }

    @Override
    public String rewrite(MediaType contentType, String body, Backend backend, HttpServletRequest req) {
        if ("html".equals(contentType.getSubtype())) {
            final String fullFrontendUrl = backend.fullFrontendUrl("");
            final String fullFrontendUrlSlash = backend.fullFrontendUrl("/");
            // Если тег указан, то относительные ссылки на ресурсы браузер считает сам.
            body = hrefPattern.matcher(body).replaceAll("href=\"" + StringUtils.removeEnd(fullFrontendUrl, "/") + "$1\"");
            if (!baseTagPattern.matcher(body).find()) {
                body = hrefPatternRel.matcher(body).replaceAll("href=\"" + fullFrontendUrlSlash + "$1\"");
            }
            body = formActionPattern.matcher(body).replaceAll("<form$1action=\"" + fullFrontendUrl + "$2\"$3>");
        }
        return body;
    }

    /**
     * Те же правила, что у regex-версии выше, но по байтам тела без {@link String}; см. {@link HtmlLinkByteRewriter}.
     */
    @Override
    public ByteBuf rewrite(ByteRewriteContext context) {
        return byteRewriter.rewrite(context);
    }
}
