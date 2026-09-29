package com.pivotos.ai.coding.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.ai.coding.api.dto.CodingSessionVO;
import com.pivotos.ai.coding.modify.ModifyService;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 修改型任务端点（A4-2 / S111）。
 *
 * <p>本端点只产出「待评审」会话（写入 DB，不碰工程文件）；落盘走既有
 * {@code POST /ai-coding/session/{id}/apply}（权限 ai:coding:apply），按 taskType=5 分派，
 * 且强制过编译/typecheck 门禁——**评审与落盘是两个动作，中间必须有人看 diff**。
 *
 * @author PivotOS
 * @since 2.14.0（S111 A4-2）
 */
@RestController
@RequestMapping("/ai-coding/modify")
@RequiredArgsConstructor
public class ModifyController {

    private final ModifyService modifyService;

    /**
     * 生成修改型补丁（定位 → edit → diff → 可应用性门禁），产物入库待评审。
     */
    @PostMapping("/parse")
    @SaCheckPermission(value = "ai:coding:parse", type = StpSysUtil.TYPE)
    public R<CodingSessionVO> parse(@RequestBody ModifyRequest request) {
        if (request == null) {
            request = new ModifyRequest();
        }
        return R.ok(modifyService.prepare(request.getDescription(), request.getRepo(), request.getModel()));
    }

    /**
     * 修改型请求体。
     */
    @lombok.Data
    public static class ModifyRequest {

        /** 自然语言改动意图 */
        private String description;

        /** 目标仓库逻辑名（fw / ui）；留空取首个登记仓库 */
        private String repo;

        /** 模型覆盖（可空，走供应商默认模型） */
        private String model;
    }
}
