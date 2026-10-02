package com.pivotos.starter.search.esjava.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端版本解析与「支持区间」判定（纯函数，离线可跑）。
 * <p>这是多版本支持的判据源头：判错就会把 7.17 判成不可用（白回落）或把 10.x 判成可用（线上炸），
 * 故把边界逐条钉死。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class EsServerVersionTest {

    @Test
    void shouldParseThreeSegmentVersion() {
        EsServerVersion v = EsServerVersion.parse("9.5.3");
        assertEquals(9, v.major());
        assertEquals(5, v.minor());
        assertEquals(3, v.patch());
        assertEquals("9.5.3", v.raw());
    }

    @Test
    void shouldParseSnapshotVersion() {
        EsServerVersion v = EsServerVersion.parse("8.19.0-SNAPSHOT");
        assertEquals(8, v.major());
        assertEquals(19, v.minor());
        assertTrue(v.isSupported());
    }

    @Test
    void shouldTreatUnparsableAsUnknown() {
        assertTrue(EsServerVersion.parse(null).isUnknown());
        assertTrue(EsServerVersion.parse("").isUnknown());
        assertTrue(EsServerVersion.parse("not-a-version").isUnknown());
        assertFalse(EsServerVersion.parse("not-a-version").isSupported());
    }

    @Test
    void shouldSupportEs717() {
        EsServerVersion v = EsServerVersion.parse("7.17.28");
        assertTrue(v.isSupported());
        assertTrue(v.needsCompatibilityHeader(), "7.x 需要 compatible-with=7 兼容头");
    }

    @Test
    void shouldNotSupportEs7Below717() {
        assertFalse(EsServerVersion.parse("7.16.3").isSupported(), "7.0~7.16 不在支持区间");
        assertTrue(EsServerVersion.parse("7.17.0").isSupported());
    }

    @Test
    void shouldSupportEs8AndEs9() {
        assertTrue(EsServerVersion.parse("8.19.0").isSupported());
        assertTrue(EsServerVersion.parse("9.5.3").isSupported());
        assertFalse(EsServerVersion.parse("8.19.0").needsCompatibilityHeader());
        assertFalse(EsServerVersion.parse("9.5.3").needsCompatibilityHeader());
    }

    @Test
    void shouldNotSupportFutureMajor() {
        assertFalse(EsServerVersion.parse("10.0.0").isSupported(), "未验证的大版本宁可回落也不赌");
        assertFalse(EsServerVersion.parse("6.8.23").isSupported());
    }

    @Test
    void shouldExposeSupportRangeText() {
        assertEquals("7.17 ~ 9.x", EsServerVersion.supportRangeText());
    }
}
