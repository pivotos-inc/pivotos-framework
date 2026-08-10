package com.pivotos.ai.coding.service;

import com.pivotos.common.core.enums.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.TreeSet;

/**
 * 错误码段分配器（S42 / 2.2-F12）。
 * <p>
 * 验收要求「错误码段不与既有段冲突（自动检查）」——不能信 LLM 输出，
 * 必须代码确定性分配：扫描 classpath 下所有 {@link ErrorCode} 实现类，
 * 收集已用千段（code/1000），取 {@value #MIN_SEGMENT} 起最小空闲段。
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Component
public class ErrorCodeSegmentAllocator {

    private static final Logger log = LoggerFactory.getLogger(ErrorCodeSegmentAllocator.class);

    /** 新插件错误码段下限（1xxx 全局 / 2xxx system / 4xxx file / 5xxx ai / 7xxx coding 已占用） */
    static final int MIN_SEGMENT = 8;

    /**
     * 分配最小空闲千段（如 8 → 错误码 8000 起）。
     *
     * @return 空闲段号（千位）
     */
    public int allocateFreeSegment() {
        Set<Integer> used = collectUsedSegments();
        int candidate = MIN_SEGMENT;
        while (used.contains(candidate)) {
            candidate++;
        }
        log.info("[AI Coding] Error code segment allocated: {}xxx (used={})", candidate, used);
        return candidate;
    }

    /**
     * 校验指定段是否空闲（评审阶段复查用）。
     */
    public boolean isSegmentFree(int segment) {
        return !collectUsedSegments().contains(segment);
    }

    /** 扫描 com.pivotos 包下全部 ErrorCode 枚举实现，收集已用千段 */
    Set<Integer> collectUsedSegments() {
        Set<Integer> segments = new TreeSet<>();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ErrorCode.class));
        for (var bd : scanner.findCandidateComponents("com.pivotos")) {
            try {
                Class<?> clazz = Class.forName(bd.getBeanClassName());
                if (!clazz.isEnum()) {
                    continue;
                }
                for (Object constant : clazz.getEnumConstants()) {
                    if (constant instanceof ErrorCode ec) {
                        segments.add(ec.getCode() / 1000);
                    }
                }
            } catch (Throwable e) {
                // 单个类加载失败不阻断分配（如 test scope 类），记录后跳过
                log.debug("[AI Coding] Skip ErrorCode candidate {}: {}", bd.getBeanClassName(), e.toString());
            }
        }
        return segments;
    }
}
