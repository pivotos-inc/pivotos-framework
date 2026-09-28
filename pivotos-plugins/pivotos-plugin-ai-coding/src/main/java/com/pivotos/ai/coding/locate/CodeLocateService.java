package com.pivotos.ai.coding.locate;

import com.pivotos.ai.coding.api.dto.LocateCandidateVO;
import com.pivotos.ai.coding.api.dto.LocatePreciseVO;
import com.pivotos.ai.coding.api.dto.LocateRequest;
import com.pivotos.ai.coding.api.dto.LocateResultVO;
import com.pivotos.ai.coding.config.LocateProperties;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.migration.api.codeindex.CodeFileEntry;
import com.pivotos.migration.api.codeindex.CodeIndexSnapshot;
import com.pivotos.migration.api.codeindex.FileSymbolTable;
import com.pivotos.migration.api.codeindex.ICodeIndexFacade;
import com.pivotos.starter.core.context.ContextExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_LOCATE_DISABLED;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_LOCATE_FAILED;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_LOCATE_INDEX_EMPTY;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_LOCATE_REPO_UNKNOWN;

/**
 * A4-1 两段定位服务：粗筛 top-N → 并行精定位 → 确定性仲裁。
 *
 * <p>链路（对应 15 号文档架构口径第 ② 步）：
 * <pre>
 *   意图 → ①粗筛：索引摘要（含分层 + 符号）→ LLM 出 top-N
 *              ∪ 确定性关键词召回（符号表字面命中，保召回下限）
 *        → ②并行精定位：每候选喂「符号表 + imports + 全文行号」→ 方法锚点与行区间
 *        → ③确定性仲裁：0.50 自评 + 0.30 符号命中 + 0.20 分层因子 → 选中唯一落点
 * </pre>
 *
 * <p>三条实证约束在此落地：spike K1（LLM 不做格式活，本 Sprint 只出结构化 JSON）、
 * K2（分层约定感知对冲上层偏好）、K3（喂 imports + 符号表抑制文件外符号幻觉）。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
@Service
public class CodeLocateService {

    private static final Logger log = LoggerFactory.getLogger(CodeLocateService.class);

    /** 粗筛阶段：意图 + 索引摘要 → top-N 候选 + 意图解析 */
    private static final String COARSE_SYSTEM_PROMPT = """
            你是 PivotOS 仓库的代码定位助手。给定「改动意图」与「仓库索引」，选出最可能需要修改的候选文件。
            索引每行格式：相对路径|分层|模块|类型名|摘要|关键符号名
            规则：
            1. LAYER 是分层约定，必须遵守：SERVICE_IMPL / VUE_PAGE / TS_API 是【实现层】，业务逻辑类改动
               （加判断、改查询、加守卫、改算法、加校验、改文案分支）默认落在实现层；
               CONTROLLER / SERVICE_API / DTO / ENTITY / VO 多为【声明层】，只有当意图明确指向接口签名、
               入参字段、实体字段、返回结构时才优先选它们。
            2. path 必须逐字来自索引，不得改写、不得臆造路径。
            3. 按可能性从高到低给出候选，confidence 为 0~1 的自评。
            4. parse.keywords 请同时给出中文关键词与代码里可能出现的英文标识符。
            只输出 JSON，不要输出任何其它文字：
            {"parse":{"domain":"业务域","change_type":"改动类型","keywords":["关键词"]},
             "candidates":[{"path":"索引中的相对路径","confidence":0.9,"reason":"理由"}]}
            """;

    /** 精定位阶段：候选文件符号表 + 全文 → 方法锚点与最小行区间 */
    private static final String PRECISE_SYSTEM_PROMPT = """
            你是 PivotOS 仓库的代码精确定位助手。给定候选文件的符号表与全文（行号: 内容），
            判断它是否为该改动意图的真正落点，并给出需要修改的最小行区间。
            规则：
            1. 只可使用文件中真实存在的符号；文件里没有的类/方法/字段一律视为不存在，禁止臆造。
            2. applicable：该文件确为落点则 true；若它只是调用方、只是声明层接口、或改动与它无关则 false。
            3. start_line/end_line 为需要修改的最小行区间（1 基闭区间，applicable=false 时给 0）。
            4. method 为目标方法名/区块名（前端可写函数名或 schema 字段名）。
            只输出 JSON，不要输出任何其它文字：
            {"method":"方法名","start_line":10,"end_line":14,"applicable":true,"confidence":0.9,"reason":"理由"}
            """;

    /** 精定位单次调用超时（秒） */
    private static final long PRECISE_TIMEOUT_SECONDS = 120;

    private final LocateProperties properties;
    private final ICodeIndexFacade indexFacade;
    private final LocateLlmClient llmClient;
    private final ObjectMapper objectMapper;

    public CodeLocateService(LocateProperties properties,
                             ICodeIndexFacade indexFacade,
                             LocateLlmClient llmClient,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.indexFacade = indexFacade;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 两段定位入口。
     *
     * @param request 定位请求
     * @return 定位结果
     */
    public LocateResultVO locate(LocateRequest request) {
        long started = System.currentTimeMillis();
        if (!properties.isEnabled()) {
            throw new ServiceException(CODING_LOCATE_DISABLED);
        }
        if (request == null || request.getIntent() == null || request.getIntent().isBlank()) {
            throw new ServiceException(CODING_LOCATE_FAILED);
        }
        LocateProperties.RepoConfig repo = properties.repo(request.getRepo());
        if (repo == null) {
            throw new ServiceException(CODING_LOCATE_REPO_UNKNOWN);
        }
        String model = resolveModel(request.getModel());
        String modelLabel = llmClient.resolveModel(model);

        CodeIndexSnapshot snapshot = Boolean.TRUE.equals(request.getRebuild())
                ? indexFacade.rebuildIndex(repo.getName(), repo.getRoot(), repo.getIncludes())
                        .orElseThrow(() -> new ServiceException(CODING_LOCATE_INDEX_EMPTY))
                : indexFacade.ensureIndex(repo.getName(), repo.getRoot(), repo.getIncludes())
                        .orElseThrow(() -> new ServiceException(CODING_LOCATE_INDEX_EMPTY));
        if (snapshot.entries().isEmpty()) {
            throw new ServiceException(CODING_LOCATE_INDEX_EMPTY);
        }

        // ① 粗筛：LLM top-N ∪ 确定性关键词召回
        Map<String, Object> parse = coarseParse(request.getIntent(), snapshot, model);
        List<String> keywords = extractKeywords(parse, request.getIntent());
        int topN = request.getTopN() == null || request.getTopN() <= 0 ? properties.getTopN() : request.getTopN();
        List<LocateCandidateVO> candidates = mergeCandidates(snapshot, parse, keywords, topN);

        // ② 并行精定位
        List<LocatePreciseVO> precise = preciseLocate(snapshot, request.getIntent(), candidates, keywords, model);

        // ③ 确定性仲裁
        boolean fallback = precise.stream().noneMatch(p -> Boolean.TRUE.equals(p.getApplicable()));
        LocatePreciseVO chosen = fallback
                ? precise.stream().max(Comparator.comparing(p -> nvl(p.getConfidence()))).orElse(null)
                : precise.stream().filter(p -> Boolean.TRUE.equals(p.getApplicable()))
                        .max(Comparator.comparing(p -> nvl(p.getScore()))).orElse(null);

        LocateResultVO result = new LocateResultVO();
        result.setRepo(repo.getName());
        result.setIntent(request.getIntent());
        result.setModel(modelLabel);
        result.setParse(parse);
        result.setCandidates(candidates);
        result.setPrecise(precise);
        result.setChosen(chosen);
        result.setFallback(fallback);
        result.setIndexSize(snapshot.size());
        result.setCostMs(System.currentTimeMillis() - started);
        log.info("[A4-1] 定位完成：repo={} model={} 候选={} 命中={} 降级={} 耗时={}ms",
                repo.getName(), model, candidates.size(),
                chosen == null ? "-" : chosen.getPath(), fallback, result.getCostMs());
        return result;
    }

    /** 索引统计（验收/运维核对用） */
    public Map<String, Integer> indexStats() {
        return indexFacade.stats();
    }

    /** 强制重建指定仓库索引 */
    public int rebuild(String repoName) {
        LocateProperties.RepoConfig repo = properties.repo(repoName);
        if (repo == null) {
            throw new ServiceException(CODING_LOCATE_REPO_UNKNOWN);
        }
        return indexFacade.rebuildIndex(repo.getName(), repo.getRoot(), repo.getIncludes())
                .map(CodeIndexSnapshot::size)
                .orElseThrow(() -> new ServiceException(CODING_LOCATE_INDEX_EMPTY));
    }

    // ---------------- ① 粗筛 ----------------

    private Map<String, Object> coarseParse(String intent, CodeIndexSnapshot snapshot, String model) {
        String digest = IndexDigestRenderer.render(snapshot);
        String user = "改动意图：" + intent + "\n\n仓库索引（路径|分层|模块|类型名|摘要|关键符号）：\n" + digest;
        String raw = llmClient.call(COARSE_SYSTEM_PROMPT, user, model);
        Map<String, Object> parsed = readMap(raw);
        if (parsed == null) {
            throw new ServiceException(CODING_LOCATE_FAILED);
        }
        return parsed;
    }

    /** 候选合并：LLM 粗筛 top-N ∪ 关键词召回 top-K，去重后按上限截断 */
    List<LocateCandidateVO> mergeCandidates(CodeIndexSnapshot snapshot, Map<String, Object> parse,
                                            List<String> keywords, int topN) {
        Map<String, CodeFileEntry> byPath = new LinkedHashMap<>();
        for (CodeFileEntry entry : snapshot.entries()) {
            byPath.put(entry.relativePath(), entry);
        }
        List<LocateCandidateVO> result = new ArrayList<>();

        // LLM 粗筛（路径逐个校验，臆造/越界路径直接丢弃——确定性闸门）
        Object rawCandidates = parse == null ? null : parse.get("candidates");
        if (rawCandidates instanceof List<?> list) {
            List<Map<String, Object>> typed = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) map;
                    typed.add(cast);
                }
            }
            typed.stream()
                    .sorted(Comparator.comparing(m -> -toDouble(m.get("confidence"))))
                    .limit(Math.max(topN, 0))
                    .forEach(m -> {
                        String path = String.valueOf(m.get("path"));
                        CodeFileEntry entry = byPath.get(path);
                        if (entry == null) {
                            log.warn("[A4-1] 粗筛路径不在索引中，已丢弃：{}", path);
                            return;
                        }
                        if (result.stream().anyMatch(c -> c.getPath().equals(path))) {
                            return;
                        }
                        LocateCandidateVO vo = new LocateCandidateVO();
                        vo.setPath(path);
                        vo.setModule(entry.moduleName());
                        vo.setLayer(entry.layer().name());
                        vo.setTypeName(entry.typeName());
                        vo.setSource("llm");
                        vo.setConfidence(toDouble(m.get("confidence")));
                        vo.setReason(String.valueOf(m.getOrDefault("reason", "")));
                        result.add(vo);
                    });
        }

        // 确定性关键词召回（补 LLM 语义召回的漏项）
        List<String> texts = new ArrayList<>(keywords);
        texts.add(0, String.valueOf(parse == null ? "" : parse.getOrDefault("domain", "")));
        for (String path : KeywordRecall.rank(snapshot, texts, properties.getKeywordTopK())) {
            if (result.stream().anyMatch(c -> c.getPath().equals(path))) {
                continue;
            }
            CodeFileEntry entry = byPath.get(path);
            if (entry == null) {
                continue;
            }
            LocateCandidateVO vo = new LocateCandidateVO();
            vo.setPath(path);
            vo.setModule(entry.moduleName());
            vo.setLayer(entry.layer().name());
            vo.setTypeName(entry.typeName());
            vo.setSource("keyword");
            vo.setConfidence(0.0);
            vo.setReason("关键词/符号字面命中召回");
            result.add(vo);
        }

        if (result.size() > properties.getMaxCandidates()) {
            return new ArrayList<>(result.subList(0, properties.getMaxCandidates()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractKeywords(Map<String, Object> parse, String intent) {
        Set<String> keywords = new LinkedHashSet<>();
        if (parse != null && parse.get("parse") instanceof Map<?, ?> raw) {
            Object list = ((Map<String, Object>) raw).get("keywords");
            if (list instanceof List<?> items) {
                for (Object item : items) {
                    if (item != null && !item.toString().isBlank()) {
                        keywords.add(item.toString());
                    }
                }
            }
        }
        keywords.addAll(KeywordRecall.tokenize(List.of(intent)));
        return new ArrayList<>(keywords);
    }

    // ---------------- ② 并行精定位 ----------------

    private List<LocatePreciseVO> preciseLocate(CodeIndexSnapshot snapshot, String intent,
                                                List<LocateCandidateVO> candidates,
                                                List<String> keywords, String model) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<String, FileSymbolTable> tables = new LinkedHashMap<>();
        for (LocateCandidateVO candidate : candidates) {
            indexFacade.symbolTable(snapshot.repo(), candidate.getPath()).ifPresent(
                    table -> tables.put(candidate.getPath(), table));
        }
        List<Future<LocatePreciseVO>> futures = new ArrayList<>(candidates.size());
        try (ExecutorService pool = ContextExecutor.wrap(Executors.newVirtualThreadPerTaskExecutor())) {
            for (LocateCandidateVO candidate : candidates) {
                FileSymbolTable table = tables.get(candidate.getPath());
                futures.add(pool.submit(() -> preciseOne(snapshot, candidate, table, intent, keywords, model)));
            }
            List<LocatePreciseVO> results = new ArrayList<>(futures.size());
            for (Future<LocatePreciseVO> future : futures) {
                try {
                    LocatePreciseVO vo = future.get(PRECISE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (vo != null) {
                        results.add(vo);
                    }
                } catch (Exception e) {
                    log.warn("[A4-1] 精定位任务失败，已跳过：{}", e.getMessage());
                }
            }
            results.sort(Comparator.comparing((LocatePreciseVO p) -> nvl(p.getScore())).reversed());
            return results;
        }
    }

    private LocatePreciseVO preciseOne(CodeIndexSnapshot snapshot, LocateCandidateVO candidate,
                                       FileSymbolTable table, String intent,
                                       List<String> keywords, String model) {
        LocatePreciseVO vo = new LocatePreciseVO();
        vo.setPath(candidate.getPath());
        vo.setLayer(candidate.getLayer());
        if (table == null) {
            vo.setApplicable(false);
            vo.setConfidence(0.0);
            vo.setReason("索引中无该文件的符号表");
            vo.setSymbolHit(0.0);
            vo.setLayerFactor(LocateArbiter.layerFactor(layerOf(candidate.getLayer())));
            vo.setScore(LocateArbiter.score(0.0, 0.0, vo.getLayerFactor()));
            return vo;
        }
        String content = indexFacade.readFile(snapshot.repo(), candidate.getPath()).orElse("");
        String feed = renderFeed(candidate, table, content);
        String user = feed + "\n\n用户改动意图：" + intent;
        Map<String, Object> parsed;
        try {
            parsed = readMap(llmClient.call(PRECISE_SYSTEM_PROMPT, user, model));
        } catch (Exception e) {
            log.warn("[A4-1] 精定位调用失败：path={} {}", candidate.getPath(), e.getMessage());
            parsed = null;
        }
        if (parsed == null) {
            vo.setApplicable(false);
            vo.setConfidence(0.0);
            vo.setReason("精定位调用失败或输出不可解析");
        } else {
            vo.setMethod(String.valueOf(parsed.getOrDefault("method", "")));
            vo.setStartLine(toInt(parsed.get("start_line")));
            vo.setEndLine(toInt(parsed.get("end_line")));
            vo.setApplicable(Boolean.TRUE.equals(parsed.get("applicable")));
            vo.setConfidence(toDouble(parsed.get("confidence")));
            vo.setReason(String.valueOf(parsed.getOrDefault("reason", "")));
        }
        vo.setSymbolHit(LocateArbiter.symbolHit(keywords, table));
        vo.setLayerFactor(LocateArbiter.layerFactor(table.layer()));
        vo.setScore(LocateArbiter.score(nvl(vo.getConfidence()), nvl(vo.getSymbolHit()), nvl(vo.getLayerFactor())));
        return vo;
    }

    /** 精定位喂入：符号表 + imports（抑制文件外符号幻觉）+ 全文行号 */
    String renderFeed(LocateCandidateVO candidate, FileSymbolTable table, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件：").append(candidate.getPath()).append('\n');
        sb.append("分层：").append(table.layer().name()).append("（实现层=").append(table.layer().implementationLayer()).append("）\n");
        sb.append("类型：").append(table.typeName());
        if (table.packageName() != null && !table.packageName().isBlank()) {
            sb.append("（package ").append(table.packageName()).append('）');
        }
        sb.append('\n');
        if (!table.imports().isEmpty()) {
            sb.append("本文件可见的外部符号（imports）：\n");
            table.imports().forEach(im -> sb.append("  - ").append(im).append('\n'));
        }
        if (!table.symbols().isEmpty()) {
            sb.append("本文件符号表（kind name(签名) @行号）：\n");
            table.symbols().forEach(s -> sb.append("  - ").append(s.kind()).append(' ')
                    .append(s.signature()).append(" @").append(s.line()).append('\n'));
        }
        sb.append("文件全文（行号: 内容）：\n");
        String[] lines = content.split("\n", -1);
        int limit = Math.min(lines.length, properties.getMaxFeedLines());
        for (int i = 0; i < limit; i++) {
            sb.append(i + 1).append(": ").append(lines[i]).append('\n');
        }
        if (limit < lines.length) {
            sb.append("...（文件共 ").append(lines.length).append(" 行，已截断至 ").append(limit).append(" 行）\n");
        }
        return sb.toString();
    }

    // ---------------- 辅助 ----------------

    private String resolveModel(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        if (properties.getModel() != null && !properties.getModel().isBlank()) {
            return properties.getModel().trim();
        }
        return null;
    }

    /** 宽容解析：剥代码围栏后取最外层 JSON 对象 */
    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            text = (start >= 0 && end > start) ? text.substring(start + 1, end).trim() : text;
        }
        int begin = text.indexOf('{');
        int finish = text.lastIndexOf('}');
        if (begin < 0 || finish <= begin) {
            return null;
        }
        try {
            return objectMapper.readValue(text.substring(begin, finish + 1),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            log.warn("[A4-1] LLM 输出 JSON 解析失败：{}", text, e);
            return null;
        }
    }

    private static com.pivotos.migration.api.codeindex.CodeLayer layerOf(String name) {
        if (name == null) {
            return com.pivotos.migration.api.codeindex.CodeLayer.UNKNOWN;
        }
        try {
            return com.pivotos.migration.api.codeindex.CodeLayer.valueOf(name);
        } catch (IllegalArgumentException e) {
            return com.pivotos.migration.api.codeindex.CodeLayer.UNKNOWN;
        }
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static int toInt(Object value) {
        return (int) Math.round(toDouble(value));
    }

    private static double nvl(Double value) {
        return value == null ? 0.0 : value;
    }
}
