package com.pivotos.starter.cloud.api.facade.fixture;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 与 {@code MessageDTO} 同构的 DTO：带 {@code LocalDateTime} 字段。
 *
 * <p><b>刻意保留无参构造</b>（与 {@code PresignResult} 那种
 * {@code @Data @AllArgsConstructor} 无无参构造的形态区分开）：
 * 无参构造缺席只影响「把返回值还原成原类型」的链路，不影响序列化，
 * 两类问题不要混在一个 fixture 里，否则定位时会互相掩盖。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BizLikeDTO {

    private Long id;

    private String name;

    /** 时间字段：没有 jsr310 模块时这里会炸或退化成嵌套对象 */
    private LocalDateTime createTime;
}
