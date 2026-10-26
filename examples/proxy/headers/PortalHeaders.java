package com.otr.portal.proxy.headers;

public interface PortalHeaders  {
    void add(String name, String value);
    boolean contains(String name);

    /**
     *  Заменит значение заголовка, если уже существует
     */
    void set(String name, String value);
}
