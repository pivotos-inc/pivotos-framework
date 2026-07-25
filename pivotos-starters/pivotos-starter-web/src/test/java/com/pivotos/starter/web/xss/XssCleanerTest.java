package com.pivotos.starter.web.xss;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * XSS 清洗器单测
 */
class XssCleanerTest {

    @Test
    void scriptTagShouldBeRemoved() {
        assertEquals("hello", XssCleaner.clean("hello<script>alert(1)</script>"));
    }

    @Test
    void javascriptProtocolShouldBeRemoved() {
        assertEquals("alert(1)", XssCleaner.clean("javascript:alert(1)"));
    }

    @Test
    void inlineEventShouldBeRemoved() {
        assertEquals("click", XssCleaner.clean("onclick=click"));
    }

    @Test
    void normalTextShouldKeep() {
        assertEquals("正常文本 normal text", XssCleaner.clean("正常文本 normal text"));
    }

    @Test
    void nullSafe() {
        assertNull(XssCleaner.clean(null));
    }
}
