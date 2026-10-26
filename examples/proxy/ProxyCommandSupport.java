package com.otr.portal.proxy;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.otr.portal.core.model.http.Header;
import com.otr.portal.hosts.Backend;
import com.otr.portal.proxy.headers.HeaderProcessor;
import com.otr.portal.proxy.headers.PortalHeaderContributor;
import com.otr.portal.proxy.logging.ProxiedRequestLifecycle;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.util.ReferenceCountUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.*;
import java.util.stream.Stream;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.lang.Integer.MAX_VALUE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

/**
 * Вспомогательные методы для {@link ProxyCommand}: построение Netty-запроса
 * и утилиты работы с HTTP/контентом.
 * <b>Код старый с минимальным рефакорингом</b>
 */
@UtilityClass
public final class ProxyCommandSupport {
    private static final Logger log = LoggerFactory.getLogger(ProxyCommandSupport.class);
    private static final Logger logContent = LoggerFactory.getLogger("HttpProxy.content");

    /**
     * Собирает полный HTTP-запрос для отправки в бэкенд.
     * <p>
     * Тело читается в pooled direct-память, поэтому его нужно освобождать: при ошибке сборки это делает сам метод,
     * дальше - Netty после записи в канал или {@link DataToSend#release()}, если запрос так и не отправлен.
     */
    public static DataToSend buildFullHttpRequest(
            HttpServletRequest req,
            String uri,
            Backend backend,
            HeaderProcessor headerProcessor,
            List<PortalHeaderContributor> enrichContributors,
            boolean keepAlive,
            ProxiedRequestLifecycle lifecycle
    ) throws HttpPostRequestEncoder.ErrorDataEncoderException, IOException {
        Stream<Header> headers = headerProcessor.collectHeaders(req, backend);
        HttpMethod method = HttpMethod.valueOf(req.getMethod());
        var bodyBuf = PooledByteBufAllocator.DEFAULT
                .compositeDirectBuffer(MAX_VALUE);
        final FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HTTP_1_1, method, uri, bodyBuf);
        try {
            readRequestBody(req, bodyBuf);
            copyRequestHeaders(nettyRequest, headers, keepAlive, enrichContributors, lifecycle);
            clearChunkedEncoding(nettyRequest);

            if ("POST".equals(req.getMethod())) {
                if (logContent.isDebugEnabled()) {
                    logContent.debug("Обрабатываем POST‑параметры для URI {}. Параметры тела запроса: {}", uri,
                            ofNullable(req.getParameterMap())
                                    .orElse(Collections.emptyMap()).entrySet().stream()
                                    .map(e -> e.getKey().concat(" : ").concat(StringUtils.join(e.getValue(), "; ")))
                                    .collect(java.util.stream.Collectors.joining(", ", "{", "}")));
                }

                boolean multipart = ofNullable(req.getContentType()).orElse("")
                        .toLowerCase().startsWith("multipart/");
                final Map<String, String[]> bodyParams = req.getParameterMap();
                final DefaultHttpDataFactory factory = new DefaultHttpDataFactory(false);
                Charset charset = Charset.isSupported(req.getCharacterEncoding())
                        ? Charset.forName(req.getCharacterEncoding())
                        : HttpConstants.DEFAULT_CHARSET;
                HttpPostRequestEncoder encoder = new HttpPostRequestEncoder(factory, nettyRequest, multipart, charset, HttpPostRequestEncoder.EncoderMode.HTML5);
                boolean useEncoder = false;
                MultiValueMap<String, String> queryParams = UriComponentsBuilder.fromUriString(uri).build().getQueryParams();

                if (MapUtils.isNotEmpty(bodyParams)) {
                    for (Map.Entry<String, String[]> paramEntry : bodyParams.entrySet()) {
                        String paramKey = paramEntry.getKey();
                        var mergedValues = asList(paramEntry.getValue());
                        var queryValues = ofNullable(queryParams.get(paramKey)).orElse(emptyList());
                        queryValues = queryValues.stream()
                                .map(v -> v == null
                                        ? ""
                                        : URLDecoder.decode(v, UTF_8)
                                )
                                .toList();
                        var mergedCount = mergedValues.stream()
                                .collect(groupingBy(identity(), counting()));
                        var queryCount = queryValues.stream().collect(groupingBy(identity(), counting()));
                        for (var bodyEntry : mergedCount.entrySet()) {
                            var value = bodyEntry.getKey();
                            var diff = bodyEntry.getValue() - queryCount.getOrDefault(value, 0L);
                            for (long i = 0; i < diff; i++) {
                                logContent.debug("Параметр '{}={}' из тела запроса. Добавляем в encoder.", paramKey, value);
                                encoder.addBodyAttribute(paramKey, value);
                                useEncoder = true;
                            }
                        }
                    }
                }
                /*
                 * при активном @see com.otr.portal.core.support.jetty.MultiPartFilterRegistration#multipartPassthrough
                 * сюда не попадём
                 */
                if (multipart && req instanceof MultipartHttpServletRequest multiPartReq) {
                    logContent.debug("Запрос является MultipartHttpServletRequest");
                    Map<String, List<MultipartFile>> filesUfos = multiPartReq.getMultiFileMap();
                    for (Map.Entry<String, List<MultipartFile>> fileEntry : filesUfos.entrySet()) {
                        for (MultipartFile multipartFile : fileEntry.getValue()) {
                            var fileContentType = ofNullable(multipartFile.getContentType())
                                    .orElse("application/octet-stream");
                            FileUpload fileUpload = factory.createFileUpload(nettyRequest, fileEntry.getKey(),
                                    multipartFile.getOriginalFilename(), fileContentType, null, null, multipartFile.getSize());
                            fileUpload.setContent(multipartFile.getInputStream());
                            encoder.addBodyHttpData(fileUpload);
                        }
                        useEncoder = true;
                    }
                }
                if (useEncoder) {
                    logBodyRebuild(req);
                    HttpRequest encoded = encoder.finalizeRequest();
                    if (encoder.isChunked()) {
                        // Тело уходит из encoder'а, а исходный буфер скрыт в WrappedHttpRequest: Netty его не освободит.
                        nettyRequest.release();
                    }
                    return new DataToSend(encoded, encoder);
                }
            }
            logContent.debug("Не используем encoder");
            syncContentLength(req, nettyRequest);

            return DataToSend.fromHttpRequest(nettyRequest);
        } catch (Throwable e) {
            nettyRequest.release();
            throw e;
        }
    }

    @Nullable
    public static MediaType parseMediaType(String mediaType) {
        try {
            return MediaType.parseMediaType(mediaType);
        } catch (InvalidMediaTypeException ime) {
            return null;
        }
    }

    public static boolean isBinaryResponse(HttpServletRequest request, MediaType contentType) {
        if (isTextAssetWithRewritableLinks(request, contentType)) {
            return false;
        }
        boolean isHtml = contentType != null
                && "text".equals(contentType.getType())
                && "html".equals(contentType.getSubtype());
        if (!isHtml) {
            return true;
        }
        String uri = request.getRequestURI();
        return StringUtils.contains(uri, ".png") || StringUtils.contains(uri, ".jpg")
                || StringUtils.contains(uri, ".woff") || StringUtils.contains(uri, ".ttf")
                || StringUtils.contains(uri, ".jpeg") || StringUtils.contains(uri, ".gif");
    }

    /**
     * CSS и ZK WCS отдаются как текст со ссылками; для них нужна та же цепочка {@code ContentRewriter}, что и для HTML.
     */
    private static boolean isTextAssetWithRewritableLinks(HttpServletRequest request, MediaType contentType) {
        String path = StringUtils.substringBefore(StringUtils.substringBefore(request.getRequestURI(), ";"), "?")
                .toLowerCase(Locale.ROOT);
        if (path.endsWith(".css") || path.endsWith(".wcs")) {
            return true;
        }
        return contentType != null && "css".equalsIgnoreCase(contentType.getSubtype());
    }

    public static Multimap<String, String> extractHeaders(HttpResponse response) {
        Multimap<String, String> result = LinkedListMultimap.create();
        io.netty.handler.codec.http.HttpHeaders headers = response.headers();
        for (Map.Entry<String, String> header : headers.entries()) {
            result.put(header.getKey(), header.getValue());
        }
        return result;
    }

    private static void readRequestBody(HttpServletRequest req, CompositeByteBuf body) throws IOException {
        if (req.getHeader(HttpHeaders.CONTENT_LENGTH) != null
                || req.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
            logContent.debug("Читаем полное тело запроса в direct - буфер");
            RequestBodyReader.readBody(req.getInputStream(), body);
            logContent.debug("Чтение тела запроса завершено");
        }
    }

    private static void copyRequestHeaders(
            FullHttpRequest nettyRequest,
            Stream<Header> headers,
            boolean keepAlive,
            List<PortalHeaderContributor> enrichContributors,
            ProxiedRequestLifecycle lifecycle) {
        io.netty.handler.codec.http.HttpHeaders entries = nettyRequest.headers();
        if (keepAlive) {
            headers = headers.filter(x ->
                    x.getName().equals(HttpHeaders.CONNECTION));
        }
        headers.forEach(header -> entries.add(header.getName(), Objects.requireNonNullElse(header.getValue(), "")));
        NettyHeadersAdapter headersAdapter = new NettyHeadersAdapter(entries);
        enrichContributors.forEach(enricher ->
                enricher.enrich(headersAdapter));
        lifecycle.processedFrontendHeaders();
    }

    /**
     * Тело уже прочитано целиком: chunked от клиента вместе с Content-Length нарушает RFC 7230.
     */
    private static void clearChunkedEncoding(FullHttpRequest nettyRequest) {
        HttpUtil.setTransferEncodingChunked(nettyRequest, false);
    }

    /**
     * Тело прочитано целиком, поэтому длина берётся по факту. Заголовок клиента при уже прочитанном до прокси потоке
     * заставил бы бэкенд ждать недостающие байты до таймаута.
     */
    private static void syncContentLength(HttpServletRequest req, FullHttpRequest nettyRequest) {
        int bodySize = nettyRequest.content().readableBytes();
        long declaredSize = req.getContentLengthLong();
        if (declaredSize >= 0 && declaredSize != bodySize) {
            log.warn("Тело запроса {} {} прочитано до прокси: заявлено {} байт, получено {}",
                    req.getMethod(), req.getRequestURI(), declaredSize, bodySize);
        }
        io.netty.handler.codec.http.HttpHeaders headers = nettyRequest.headers();
        if (bodySize > 0 || headers.contains(HttpHeaders.CONTENT_LENGTH)) {
            headers.set(HttpHeaders.CONTENT_LENGTH, bodySize);
        }
    }

    /**
     * Пересборка ожидаема только для multipart, разобранного {@code MultipartFilter} при отключённой
     * передаче как есть ({@code portal.proxy.multipart-passthrough=false}). В остальных случаях тело
     * прочитал кто-то до прокси, и бэкенд получит не исходные байты.
     */
    private static void logBodyRebuild(HttpServletRequest req) {
        if (req instanceof MultipartHttpServletRequest) {
            logContent.debug("Используем encoder для пересборки тела запроса");
        } else {
            log.warn("Тело запроса {} {} прочитано до прокси, пересобираем его из параметров",
                    req.getMethod(), req.getRequestURI());
        }
    }

    @Value
    @RequiredArgsConstructor
    public static class DataToSend {
        HttpRequest httpRequest;
        HttpPostRequestEncoder encoder;

        static DataToSend fromHttpRequest(FullHttpRequest nettyRequest) {
            return new DataToSend(nettyRequest, null);
        }

        /**
         * Освобождает запрос, который не записан в канал. После записи тело освобождает Netty.
         * Для chunked-запроса из encoder'а {@code httpRequest} не владеет буфером, и освобождаются только данные формы.
         */
        public void release() {
            ReferenceCountUtil.release(httpRequest);
            if (encoder != null) {
                encoder.cleanFiles();
            }
        }

        public boolean isChunked() {
            return encoder != null && encoder.isChunked();
        }
    }
}
