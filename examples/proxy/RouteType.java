package com.otr.portal.proxy;

/**
 * Типы запросов в контексте логики приложения</br>
 * <b>В настоящий момент применяется ограниченно</b>
 *
 * @author alexraspopin
 * @since 27.05.2026
 */
public enum RouteType {
    LOCAL, // API/ресурсы и.т.д
    LEGACY_PROXY,
    PREFIXED_PROXY,
    UNKNOWN
}
