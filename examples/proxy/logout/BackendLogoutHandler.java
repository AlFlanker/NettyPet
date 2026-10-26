package com.otr.portal.proxy.logout;

import com.otr.portal.core.security.logout.LogoutHandler;
import com.otr.portal.hosts.Backend;
import com.otr.portal.hosts.HostsService;
import com.otr.portal.hosts.filter.BackendForRequestFilter;
import com.otr.portal.proxy.HttpProxy;
import com.otr.portal.proxy.NettyProxyRequestExecutor;
import com.otr.portal.proxy.cookies.Cookie;
import com.otr.portal.proxy.cookies.ProxiedCookie;
import com.otr.portal.proxy.headers.DeletedCookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Обработчик выхода пользователя из текущей сессии Портала
 *
 * @author gusev.aleksandr
 * @since 30.01.2018
 */
@Service
public class BackendLogoutHandler implements LogoutHandler {
    private static final Logger log = LoggerFactory.getLogger(NettyProxyRequestExecutor.class);

    private HostsService hostsService;
    private HttpProxy httpProxy;

    public BackendLogoutHandler(HostsService hostsService,
                                HttpProxy httpProxy) {
        this.hostsService = hostsService;
        this.httpProxy = httpProxy;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        Stream<Cookie> allCookie = Stream.empty();
        for (Backend backend : hostsService.getBackends()) {
            //забираем хидеры с куками
            String cookieHeader = getCookieHeadersFromBackend(backend, request, response);
            Stream<ProxiedCookie> proxiedCookiesStream;

            //ищем куки, начинающиеся с PROXY$
            proxiedCookiesStream = Stream.of(Optional.ofNullable(cookieHeader).map(h -> StringUtils.splitByWholeSeparator(h, "; ")).orElse(new String[0]))
                    .map(Cookie::parse)
                    .filter(Objects::nonNull)
                    .filter(ProxiedCookie::isProxiedCookie)
                    .map(ProxiedCookie::parseProxied);

            //если используется использование uri, то дополнительно проверяем, что окончание имени куки равно $$<uri бэкенда>
            if (backend.isUseUris()) {
                proxiedCookiesStream = proxiedCookiesStream.filter(cookie -> StringUtils.equals(cookie.backend(), backend.getUri()));
            }

            //если для бэкенда не указан logoutUri, значит будем просто "убивать" все его куки
            Stream<DeletedCookie> deletedCookiesStream = Stream.empty();
            if (StringUtils.isBlank(backend.getLogoutUri())) {
                deletedCookiesStream = proxiedCookiesStream.map(DeletedCookie::of);
            }

            allCookie = Stream.concat(allCookie, deletedCookiesStream);
        }

        //по всем найденным кукам формируем хидер на изменение значений тех самых кук
        String setCookieHeaderValue = allCookie.map(cookie -> {
            cookie.resetPath();
            return cookie.toString();
        }).collect(Collectors.joining("; "));
        response.addHeader(HttpHeaders.SET_COOKIE, setCookieHeaderValue);

    }

    /**
     * Получение кук для выхода текущего бэкенда
     *
     * @param backend  бэкенд, для которого осуществляется логаут
     * @param request  запрос по которому осуществляется логаут
     * @param response ответ пользователю
     * @return полученый хидер
     */
    private String getCookieHeadersFromBackend(Backend backend, HttpServletRequest request, HttpServletResponse response) {
        String result;
        if (StringUtils.isNotBlank(backend.getLogoutUri())) {
            ResponseWithBackendCookie cookieWrapper = new ResponseWithBackendCookie(response);
            try {
                httpProxy.load(backend.getLogoutUri(), new BackendRequestWrapper(request, backend, backend.appendPrefixTo(backend.getLogoutUri())), cookieWrapper);
            } catch (Exception ex) {
                log.error("Ошибка логаута бэкенда {}", backend.name(), ex);
            }

            result = cookieWrapper.getCookieHeader();
        } else {
            result = request.getHeader(HttpHeaders.COOKIE);
        }

        return result;
    }

    /**
     * Ответ, из которого заберем куки целевой системы
     */
    private class ResponseWithBackendCookie extends HttpServletResponseWrapper {

        String cookieHeader;

        ResponseWithBackendCookie(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setHeader(String name, String value) {
            if (HttpHeaders.SET_COOKIE.equals(name)) {
                cookieHeader = value;
            } else {
                super.setHeader(name, value);
            }
        }

        @Override
        public void addHeader(String name, String value) {
            if (HttpHeaders.SET_COOKIE.equals(name)) {
                cookieHeader = value;
            } else {
                super.addHeader(name, value);
            }
        }

        String getCookieHeader() {
            return cookieHeader;
        }
    }

    /**
     * Запрос в целевую систему, в котором для атрибута {@link BackendForRequestFilter#BACKEND_ATTRIBUTE_NAME}
     * вернется бэкенд данной целевой системы
     */
    private class BackendRequestWrapper extends HttpServletRequestWrapper {
        private final Backend backend;
        private final String targetRequestUri;

        BackendRequestWrapper(HttpServletRequest request, Backend backend, String targetRequestUri) {
            super(request);
            this.backend = backend;
            this.targetRequestUri = targetRequestUri;
        }

        @Override
        public Object getAttribute(String name) {
            if (BackendForRequestFilter.BACKEND_ATTRIBUTE_NAME.equals(name)) {
                return backend;
            }
            return super.getAttribute(name);
        }

        @Override
        public String getRequestURI() {
            return targetRequestUri;
        }
    }
}