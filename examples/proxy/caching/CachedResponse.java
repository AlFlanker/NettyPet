package com.otr.portal.proxy.caching;

import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multimap;

import java.util.Date;

/**
 * @author pobedenniy.alexey
 * @since 23.04.18
 */
public class CachedResponse {
    private final Multimap<String, String> headers = MultimapBuilder
            .treeKeys(String.CASE_INSENSITIVE_ORDER).arrayListValues().build();
    private byte[] content;
    private int status;
    private Date validThrough;

    public void addHeader(String name, String value) {
        headers.put(name, value);
    }

    public Multimap<String, String> getHeaders() {
        return headers;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }

    public byte[] getContent() {
        return content;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public void setValidThrough(Date validThrough) {
        this.validThrough = validThrough;
    }

    public Date getValidThrough() {
        return validThrough;
    }

    public boolean invalid() {
        return validThrough != null && validThrough.before(new Date());
    }
}
