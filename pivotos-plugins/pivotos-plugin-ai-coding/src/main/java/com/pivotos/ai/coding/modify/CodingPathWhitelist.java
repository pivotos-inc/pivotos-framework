package com.pivotos.ai.coding.modify;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 落盘路径白名单（单一事实源）。
 *
 * <p>CRUD 生成（CrudApplyService）与修改型应用（A4-2）共用同一组白名单——
 * 修改型链路的落点由 LLM 间接决定，**必须**与生成型走同一道越界闸门（7009），
 * 否则「AI 改代码」就成了任意文件写入口。此处从 CrudApplyService 提取为共享常量，
 * 两处引用同一份，避免白名单漂移。
 *
 * @author PivotOS
 * @since 2.14.0（S111 A4-2）
 */
public final class CodingPathWhitelist {

    /** 后端插件源码与资源 */
    public static final Pattern PLUGIN_FILE = Pattern.compile(
            "^pivotos-plugins/pivotos-plugin-[a-z0-9-]+/src/main/(java|resources)/.+");

    /** PC 管理端（接口层与页面） */
    public static final Pattern PC_FILE = Pattern.compile(
            "^pivotos-ui/apps/admin/src/(api|views)/.+");

    /** 移动端 */
    public static final Pattern APP_FILE = Pattern.compile(
            "^pivotos-app/src/(api|pages-gen)/.+");

    private CodingPathWhitelist() {
    }

    /** PC 仓根目录名（与 {@link #PC_FILE} 前缀同源，改仓名须同步） */
    public static final String UI_DIR = "pivotos-ui";

    /** 移动端仓根目录名（与 {@link #APP_FILE} 前缀同源，改仓名须同步） */
    public static final String APP_DIR = "pivotos-app";

    /**
     * 判断相对路径是否被允许落盘（禁 {@code ..}，且须命中三张白名单之一）。
     *
     * <p><b>基准</b>：本方法按「四仓父目录相对」判定——生成型产物路径（{@code pivotos-ui/apps/...}）
     * 即为此基准；但后端 {@link #PLUGIN_FILE} 历史上写作仓内形式（{@code pivotos-plugins/...}，
     * 不含 {@code pivotos-framework/} 前缀），属既有混基事实，此处不擅自改（改了会崩生成型）。
     */
    public static boolean allows(String relativePath) {
        if (relativePath == null || relativePath.contains("..")) {
            return false;
        }
        return PLUGIN_FILE.matcher(relativePath).matches()
                || PC_FILE.matcher(relativePath).matches()
                || APP_FILE.matcher(relativePath).matches();
    }

    /**
     * 按<b>仓内相对路径</b>判定是否允许落盘（修改型专用入口）。
     *
     * <p><b>为什么不能直接用 {@link #allows(String)}</b>：A4-1 定位产出的 {@code chosen.path}
     * 相对「仓库 root」（{@code apps/admin/src/api/system/post.ts}），而白名单是四仓父目录基准
     * （{@code pivotos-ui/apps/...}）——S111 首轮 E2E 的 I6/I7/I8 三条 UI 意图因此全部被 7009
     * 误杀（不是越权，是基准不一致）。此处按仓根目录名补回前缀后再走同一张白名单，
     * **闸门强度不变**（仍然只有那三类路径可写）。
     *
     * @param repoRoot     仓库根（{@code locate.repos[].root}）
     * @param relativePath 仓内相对路径
     */
    public static boolean allowsInRepo(Path repoRoot, String relativePath) {
        if (relativePath == null || relativePath.contains("..") || repoRoot == null) {
            return false;
        }
        String dir = dirName(repoRoot);
        if (UI_DIR.equals(dir)) {
            return PC_FILE.matcher(UI_DIR + "/" + relativePath).matches();
        }
        if (APP_DIR.equals(dir)) {
            return APP_FILE.matcher(APP_DIR + "/" + relativePath).matches();
        }
        return PLUGIN_FILE.matcher(relativePath).matches();
    }

    /** 仓根目录名（root 末段）；用于区分三仓以对齐白名单基准 */
    public static String dirName(Path repoRoot) {
        Path name = repoRoot == null ? null : repoRoot.getFileName();
        return name == null ? "" : name.toString();
    }

    /**
     * 由落盘路径推断后端 Maven 模块（供编译门禁 {@code -pl} 用）；非后端路径返回 null。
     */
    public static String backendModuleOf(String relativePath) {
        if (relativePath == null || !relativePath.startsWith("pivotos-plugins/pivotos-plugin-")) {
            return null;
        }
        int idx = relativePath.indexOf("/src/main/");
        if (idx < 0) {
            return null;
        }
        return relativePath.substring(0, idx);
    }
}
