package com.pivotos.ai.coding.locate;

import com.pivotos.migration.api.codeindex.CodeFileEntry;
import com.pivotos.migration.api.codeindex.CodeIndexSnapshot;
import com.pivotos.migration.api.codeindex.CodeLayer;
import com.pivotos.migration.api.codeindex.CodeSymbol;
import com.pivotos.migration.api.codeindex.FileSymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 确定性关键词召回测试（粗筛召回下限的保障）。
 *
 * <p>针对 spike 唯一 miss（I4：coding 会话应用被 AI 聊天域截获）与系统性偏向上层两类失效，
 * 断言「符号命中的文件必须排在纯路径命中的文件之前」，且臆造路径不会被召回。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
@DisplayName("KeywordRecall 测试")
class KeywordRecallTest {

    private static final String CODING_IMPL =
            "pivotos-plugins/pivotos-plugin-ai-coding/src/main/java/com/pivotos/ai/coding/service/impl/CodingServiceImpl.java";
    private static final String CHAT_IMPL =
            "pivotos-plugins/pivotos-plugin-ai/src/main/java/com/pivotos/ai/service/impl/AiChatServiceImpl.java";

    @Test
    @DisplayName("符号名命中优先于路径/模块命中：I4 形态不再被聊天域截获")
    void symbolHitBeatsPathHit() {
        CodeIndexSnapshot snapshot = snapshot();
        List<String> ranked = KeywordRecall.rank(snapshot,
                List.of("AI 代码生成会话应用到工程时加状态守卫：只有待评审状态才允许应用", "applyToProject"), 2);
        assertFalse(ranked.isEmpty());
        assertEquals(CODING_IMPL, ranked.get(0), "符号命中的 CodingServiceImpl 必须排在首位");
        assertFalse(ranked.contains(CHAT_IMPL),
                "聊天域实现无任何符号/路径/类型命中，不进确定性召回集（语义召回若带进来，由精定位+仲裁淘汰）");
    }

    @Test
    @DisplayName("分词：骆驼峰拆解 + 短 token 过滤（token 一律小写）")
    void tokenize() {
        List<String> tokens = KeywordRecall.tokenize(List.of("deleteTemplate 消息模板"));
        assertTrue(tokens.contains("deletetemplate"), "原 token 小写化后入表");
        assertTrue(tokens.contains("delete"), "骆驼峰应拆出 delete");
        assertTrue(tokens.contains("template"), "骆驼峰应拆出 template");
        assertFalse(tokens.contains("to"), "过短骆驼峰片段应被过滤");
    }

    @Test
    @DisplayName("打分：符号命中 4 分 > 类型名 3 分 > 模块/路径 1 分")
    void scoringWeights() {
        CodeFileEntry symbolHit = new CodeFileEntry("apps/admin/src/api/system/post.ts", "apps/admin",
                CodeLayer.TS_API, "post", "", List.of("exportPosts"), 33);
        assertEquals(4, KeywordRecall.scoreEntry(symbolHit, List.of("exportposts")), "符号命中 4 分");

        CodeFileEntry typeHit = new CodeFileEntry("apps/admin/src/views/system/post/index.vue", "apps/admin",
                CodeLayer.VUE_PAGE, "TemplateCard", "", List.of("render"), 100);
        assertEquals(3, KeywordRecall.scoreEntry(typeHit, List.of("template")), "类型名命中 3 分");

        CodeFileEntry pathHit = new CodeFileEntry("apps/admin/src/api/system/post.ts", "apps/admin",
                CodeLayer.TS_API, "x", "", List.of("foo"), 33);
        assertEquals(1, KeywordRecall.scoreEntry(pathHit, List.of("system")), "路径命中 1 分");

        CodeFileEntry noHit = new CodeFileEntry("apps/admin/src/api/system/user.ts", "apps/admin",
                CodeLayer.TS_API, "user", "", List.of("importUsersStream"), 176);
        assertEquals(0, KeywordRecall.scoreEntry(noHit, List.of("exportposts")), "无命中 0 分");
    }

    @Test
    @DisplayName("空输入与零 topK 安全返回")
    void emptyInputs() {
        CodeIndexSnapshot snapshot = snapshot();
        assertTrue(KeywordRecall.rank(snapshot, List.of(), 5).isEmpty());
        assertTrue(KeywordRecall.rank(snapshot, List.of("applyToProject"), 0).isEmpty());
        assertTrue(KeywordRecall.rank(null, List.of("x"), 5).isEmpty());
        assertTrue(KeywordRecall.rank(new CodeIndexSnapshot("fw", "/tmp", List.of(), Map.of()),
                List.of("x"), 5).isEmpty());
    }

    private CodeIndexSnapshot snapshot() {
        FileSymbolTable coding = new FileSymbolTable(CODING_IMPL, CodeLayer.SERVICE_IMPL,
                "CodingServiceImpl", "AI 代码生成服务实现", "com.pivotos.ai.coding.service.impl",
                List.of(), List.of(new CodeSymbol("applyToProject", "method", "void applyToProject(Long, Long)", 159)),
                575);
        FileSymbolTable chat = new FileSymbolTable(CHAT_IMPL, CodeLayer.SERVICE_IMPL,
                "AiChatServiceImpl", "AI 对话服务实现", "com.pivotos.ai.service.impl",
                List.of(), List.of(new CodeSymbol("chat", "method", "String chat(String)", 40)), 300);
        List<CodeFileEntry> entries = List.of(
                new CodeFileEntry(CODING_IMPL, "pivotos-plugins/pivotos-plugin-ai-coding",
                        CodeLayer.SERVICE_IMPL, "CodingServiceImpl", "AI 代码生成服务实现",
                        List.of("applyToProject", "parseAndGenerate"), 575),
                new CodeFileEntry(CHAT_IMPL, "pivotos-plugins/pivotos-plugin-ai",
                        CodeLayer.SERVICE_IMPL, "AiChatServiceImpl", "AI 对话服务实现",
                        List.of("chat"), 300));
        return new CodeIndexSnapshot("fw", "/tmp/repo", entries,
                Map.of(CODING_IMPL, coding, CHAT_IMPL, chat));
    }
}
