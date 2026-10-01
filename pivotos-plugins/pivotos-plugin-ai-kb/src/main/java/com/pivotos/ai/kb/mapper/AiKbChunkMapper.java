package com.pivotos.ai.kb.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.kb.domain.entity.AiKbChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 知识库文本块 Mapper（BM25 检索用）。
 *
 * <p>跳过租户行级过滤：ai_kb_chunk 通过 kb_id 已做知识库级隔离，
 * 无需额外的 tenant_id 条件。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface AiKbChunkMapper extends BaseMapper<AiKbChunk> {

    /**
     * 批量插入文本块。
     * <p><b>必须回填自增主键</b>（S128）：文本块要按主键写全文索引，
     * 主键为空时 {@code SearchEntityMapper#resolveDocId} 无法确定文档 id，
     * 索引会整批失败且只留一条 WARN（症状是「索引里始终没有块」）。
     *
     * @param list 文本块列表
     */
    @Options(useGeneratedKeys = true, keyProperty = "id")
    @Insert({
            "<script>",
            "INSERT INTO ai_kb_chunk (kb_id, doc_id, chunk_index, content, content_hash, tenant_id, create_time)",
            "VALUES",
            "<foreach collection='list' item='item' separator=','>",
            "(#{item.kbId}, #{item.docId}, #{item.chunkIndex}, #{item.content}, #{item.contentHash}, #{item.tenantId}, NOW())",
            "</foreach>",
            "</script>"
    })
    void batchInsert(@Param("list") List<AiKbChunk> list);

    /**
     * 加载知识库全部文本块（BM25 检索用）。
     *
     * @param kbId 知识库ID
     * @return 文本块列表
     */
    @Select("SELECT * FROM ai_kb_chunk WHERE kb_id = #{kbId} ORDER BY id ASC")
    List<AiKbChunk> selectByKbId(@Param("kbId") Long kbId);

    /**
     * 加载指定文档全部文本块（分块查看/解析预览用）。
     *
     * @param docId 文档ID
     * @return 文本块列表（按块序号升序）
     */
    @Select("SELECT * FROM ai_kb_chunk WHERE doc_id = #{docId} ORDER BY chunk_index ASC")
    List<AiKbChunk> selectByDocId(@Param("docId") Long docId);

    /**
     * 删除知识库全部文本块。
     *
     * @param kbId 知识库ID
     * @return 删除行数
     */
    @Delete("DELETE FROM ai_kb_chunk WHERE kb_id = #{kbId}")
    int deleteByKbId(@Param("kbId") Long kbId);

    /**
     * 删除指定文档全部文本块。
     *
     * @param docId 文档ID
     * @return 删除行数
     */
    @Delete("DELETE FROM ai_kb_chunk WHERE doc_id = #{docId}")
    int deleteByDocId(@Param("docId") Long docId);
}
