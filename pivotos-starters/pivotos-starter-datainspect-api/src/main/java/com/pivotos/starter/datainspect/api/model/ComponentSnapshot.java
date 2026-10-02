package com.pivotos.starter.datainspect.api.model;

import com.pivotos.starter.datainspect.api.enums.Capability;
import com.pivotos.starter.datainspect.api.enums.DataSourceType;
import com.pivotos.starter.datainspect.api.enums.RejectReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 组件快照：前端左树直接消费；不可用时给出可读原因而不是报错。
 *
 * <p>必须同时给「是否可用 / 原因码 / 原因文案 / 能力集」，缺一就看不出回落。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComponentSnapshot {

    private String type;

    private String name;

    private boolean available;

    private String reasonCode;

    private String reason;

    /** 版本 / 地址等补充信息（不做敏感披露，仅服务端可辨识） */
    private String detail;

    private Set<Capability> capabilities;

    public static ComponentSnapshot of(DataSourceType type, boolean available, RejectReason reason,
                                       String detail, Set<Capability> capabilities) {
        return ComponentSnapshot.builder()
                .type(type.getCode())
                .name(type.getLabel())
                .available(available)
                .reasonCode(reason == null ? null : reason.name())
                .reason(reason == null ? null : reason.text(detail))
                .detail(detail)
                .capabilities(capabilities == null ? new LinkedHashSet<>() : capabilities)
                .build();
    }

    /** 不可用的统一构造（含未装配的扩展点占位） */
    public static ComponentSnapshot unavailable(DataSourceType type, RejectReason reason, String detail) {
        return of(type, false, reason, detail, new LinkedHashSet<>());
    }

    public List<String> capabilityCodes() {
        List<String> codes = new ArrayList<>();
        if (capabilities != null) {
            capabilities.forEach(c -> codes.add(c.name()));
        }
        return codes;
    }
}
