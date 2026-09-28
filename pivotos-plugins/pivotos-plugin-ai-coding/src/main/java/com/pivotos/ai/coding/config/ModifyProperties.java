package com.pivotos.ai.coding.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 修改型任务配置（A4-2 / S111）。
 *
 * <p>默认关闭：修改型会写工程文件，能力必须显式开启；仓库根沿用定位配置（locate.repos）。
 *
 * @author PivotOS
 * @since 2.14.0（S111 A4-2）
 */
@Data
@ConfigurationProperties(prefix = "pivotos.ai.coding.modify")
public class ModifyProperties {

    /** 修改型能力总开关（默认关闭） */
    private boolean enabled = false;

    /** 自动门禁 */
    private Gate gate = new Gate();

    @Data
    public static class Gate {

        /** 落盘后是否执行编译/typecheck 门禁（默认开启；失败即回滚） */
        private boolean buildEnabled = true;

        /** 单条门禁命令超时（秒） */
        private int timeoutSeconds = 600;

        /**
         * 后端编译命令模板（{module} 占位替换为 Maven 模块路径）。
         * 留空则用内置默认：mvn -o -q compile -pl {module} -DskipTests
         */
        private String fwCommand = "";

        /**
         * 前端类型检查命令（在 pivotos-ui 根目录执行）。
         * 留空则用内置默认：pnpm typecheck
         */
        private String uiCommand = "";

        /** Maven 可执行文件路径（本机固定口径，见项目记忆 §3） */
        private String mavenPath = "/Users/huweilong/Documents/File/Plugin/apache-maven-3.9.16/bin/mvn";

        /** node/pnpm 可执行文件所在 PATH（本机须 /usr/local/bin 优先，见项目记忆踩坑 28） */
        private String nodePath = "/usr/local/bin:/usr/bin:/bin";
    }
}
