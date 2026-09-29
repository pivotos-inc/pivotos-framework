package com.pivotos.ai.coding.modify;

import com.pivotos.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_EDIT_PARSE_FAILED;
import static org.junit.jupiter.api.Assertions.*;

/**
 * edit 指令解析测试：容错但不放水。
 *
 * @author PivotOS
 * @since 2.14.0（S111 A4-2）
 */
@DisplayName("EditInstructionParser 测试")
class EditInstructionParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("标准 JSON + ```json 包裹均可解析")
    void parseStandard() {
        String raw = """
                ```json
                {"path":"a/b/C.java","edits":[{"search":"int x = 1;","replace":"int x = 2;","occurrence":0,"reason":"改值"}]}
                ```
                """;
        EditInstruction instruction = EditInstructionParser.parse(raw, "fallback/C.java", mapper);
        assertEquals("a/b/C.java", instruction.path());
        assertEquals(1, instruction.blocks().size());
        assertEquals("int x = 1;", instruction.blocks().get(0).search());
        assertEquals("改值", instruction.blocks().get(0).reason());
    }

    @Test
    @DisplayName("path 缺失回落定位路径；edits 兼容 blocks 键名")
    void fallbacks() {
        String raw = "{\"blocks\":[{\"search\":\"a\",\"replace\":\"b\"}]}";
        EditInstruction instruction = EditInstructionParser.parse(raw, "fallback/C.java", mapper);
        assertEquals("fallback/C.java", instruction.path());
        assertEquals(1, instruction.blocks().size());
    }

    @Test
    @DisplayName("append 块允许无 search；非 append 块缺 search 一律 7018")
    void searchRequiredUnlessAppend() {
        String appendOk = "{\"path\":\"p\",\"edits\":[{\"append\":true,\"replace\":\"x\"}]}";
        assertEquals(1, EditInstructionParser.parse(appendOk, "p", mapper).blocks().size());

        String bad = "{\"path\":\"p\",\"edits\":[{\"replace\":\"x\"}]}";
        ServiceException e = assertThrows(ServiceException.class,
                () -> EditInstructionParser.parse(bad, "p", mapper));
        assertEquals(CODING_EDIT_PARSE_FAILED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("非 JSON / 空 edits / 无 path 兜底 → 7018")
    void invalidInputs() {
        assertThrows(ServiceException.class, () -> EditInstructionParser.parse("这不是 JSON", "p", mapper));
        assertThrows(ServiceException.class, () -> EditInstructionParser.parse(null, "p", mapper));
        assertThrows(ServiceException.class, () -> EditInstructionParser.parse(
                "{\"path\":\"p\",\"edits\":[]}", "p", mapper));
        assertThrows(ServiceException.class, () -> EditInstructionParser.parse(
                "{\"edits\":[{\"search\":\"a\"}]}", null, mapper));
    }

    @Test
    @DisplayName("sanitize：丢弃空 search 的非 append 块")
    void sanitizeDropsUnverifiableBlocks() {
        var blocks = EditInstruction.sanitize(java.util.List.of(
                new EditInstruction.Block("", "x", 0, false, "不可校验"),
                new EditInstruction.Block("a", "b", 0, false, "可校验"),
                new EditInstruction.Block(null, "c", 0, true, "追加")));
        assertEquals(2, blocks.size());
        assertEquals("a", blocks.get(0).search());
        assertTrue(blocks.get(1).append());
    }
}
