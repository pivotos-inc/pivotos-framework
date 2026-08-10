package com.pivotos.ai.coding.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ErrorCodeSegmentAllocator.
 * <p>
 * 测试 classpath 含 CodingErrorCode（7xxx）与 GlobalErrorCode（1xxx），
 * 最小空闲段应为 8（MIN_SEGMENT）。
 */
@DisplayName("ErrorCodeSegmentAllocator unit tests")
class ErrorCodeSegmentAllocatorTest {

    private final ErrorCodeSegmentAllocator allocator = new ErrorCodeSegmentAllocator();

    @Test
    @DisplayName("collect finds known segments (1xxx global, 7xxx coding)")
    void testCollectUsedSegments() {
        Set<Integer> used = allocator.collectUsedSegments();
        assertTrue(used.contains(1), "应发现全局 1xxx 段");
        assertTrue(used.contains(7), "应发现 AI Coding 7xxx 段");
    }

    @Test
    @DisplayName("allocate returns first free segment >= MIN_SEGMENT")
    void testAllocateFreeSegment() {
        int seg = allocator.allocateFreeSegment();
        assertTrue(seg >= ErrorCodeSegmentAllocator.MIN_SEGMENT);
        assertFalse(allocator.collectUsedSegments().contains(seg));
    }

    @Test
    @DisplayName("isSegmentFree consistent with collect")
    void testIsSegmentFree() {
        assertFalse(allocator.isSegmentFree(7));
        assertTrue(allocator.isSegmentFree(9));
    }
}
