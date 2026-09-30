package com.pivotos.ai.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.api.usage.AiUsageContext;
import com.pivotos.ai.client.AiClientRegistry;
import com.pivotos.ai.domain.dto.ApprovalAdviceRequest;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.entity.AiApprovalAdvice;
import com.pivotos.ai.domain.entity.AiProvider;
import com.pivotos.ai.domain.vo.ApprovalAdviceVO;
import com.pivotos.ai.domain.vo.ApprovalReferenceVO;
import com.pivotos.ai.kb.api.dto.KbOptionDTO;
import com.pivotos.ai.kb.api.dto.KbSearchResultDTO;
import com.pivotos.ai.kb.api.enums.KbType;
import com.pivotos.ai.kb.api.facade.IKnowledgeBaseFacade;
import com.pivotos.ai.mapper.AiApprovalAdviceMapper;
import com.pivotos.ai.orchestrator.ApprovalAdviceProperties;
import com.pivotos.ai.orchestrator.AutoApprovalPolicy;
import com.pivotos.ai.domain.vo.AutoApprovalResultVO;
import com.pivotos.ai.service.AiApprovalAdviceService;
import com.pivotos.ai.service.AiProviderService;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.workflow.api.IWorkflowFacade;
import com.pivotos.workflow.api.dto.ApprovalTaskContextDTO;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 审批建议服务实现（S101 A3）
 *
 * <p>链路：IWorkflowFacade 聚合待办上下文（含审批人归属闸）→ 默认库策略检索制度知识库 →
 * ChatClient 流式生成结构化建议（JSON：conclusion/reason）→ SSE 帧推送 → 落库留痕。
 *
 * <p>姿势复用对话流式链路（AiChatServiceImpl）：SseEmitter(0L) + Flux 桥接；
 * 流式已发 meta 不换 Key 重试；回调线程无 LoginContext，落库显式补齐审计字段，
 * tenantId 在请求线程快照后带入回调。
 *
 * <p>references 由服务端从检索片段确定性构建（chunkId 模型无从知晓，防模型破坏结构），
 * 模型只负责 conclusion + reason 两字段；解析失败降级 need_info + 原文入 reason，不断链。
 */
@Service
@RequiredArgsConstructor
public class AiApprovalAdviceServiceImpl implements AiApprovalAdviceService {

    private static final Logger log = LoggerFactory.getLogger(AiApprovalAdviceServiceImpl.class);

    /** 制度检索召回条数上限（控 token 消耗，简报风险表拍板） */
    private static final int ADVICE_TOP_K = 5;
    /** 依据摘录展示长度（落库 references_json 的 quote 截断） */
    private static final int QUOTE_MAX_LENGTH = 200;
    /** 检索查询长度上限（流程名 + 变量摘要） */
    private static final int SEARCH_QUERY_MAX = 200;
    /** 单个流程变量值展示长度上限（防超长值撑爆 prompt） */
    private static final int VARIABLE_VALUE_MAX = 200;
    /** 合法结论三态 */
    private static final Set<String> VALID_CONCLUSIONS = Set.of("approve", "reject", "need_info");
    /** 免责声明（done/meta 帧携带；次轮前端落文案） */
    private static final String DISCLAIMER = "AI 建议仅供参考，审批责任仍归审批人";

    /** 建议生成 system 提示词：三态约束 + 禁编造条款 + 只输出 JSON */
    private static final String ADVICE_SYSTEM_PROMPT = """
            你是企业审批合规助手。基于给定的审批任务上下文与制度参考资料，为审批人生成一条审批建议。\
            硬性约束：\
            1. 只输出一个 JSON，不输出任何其他内容：{"conclusion":"...","reason":"..."}。\
            2. conclusion 只能三选一：approve=建议通过；reject=建议驳回；need_info=材料不足需补充。\
            3. reason 用中文不超过 200 字，引用参考资料须带编号（如 [1]）。\
            4. 依据必须来自给定参考资料，禁止编造制度条款；资料缺失或不足以支撑判断时，\
            conclusion 必须为 need_info 并说明缺少什么材料。""";

    private final AiApprovalAdviceMapper adviceMapper;
    /** 工作流门面（可选依赖：workflow 插件未装配时按任务不存在处理） */
    private final ObjectProvider<IWorkflowFacade> workflowFacadeProvider;
    /** 知识库门面（可选依赖：kb 插件未部署时走无制度依据降级） */
    private final ObjectProvider<IKnowledgeBaseFacade> kbFacadeProvider;
    /** 静态兜底 ChatClient（spring.ai.openai.* 未配置时不存在），懒获取 + 5020 兜底 */
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final AiProviderService aiProviderService;
    private final AiClientRegistry clientRegistry;
    private final Environment environment;
    /** A4E 受控自动预审开关（S117；默认关闭） */
    private final ApprovalAdviceProperties autoApproveProperties;

    @Override
    public SseEmitter streamAdvice(Long userId, ApprovalAdviceRequest request) {
        // S92 用量场景标注：审批建议链路；计量快照在 flux 组装时于请求线程抓取
        AiUsageContext.setScene(AiUsageContext.SCENE_APPROVAL);
        try {
            long startMs = System.currentTimeMillis();
            ApprovalTaskContextDTO taskContext = loadTaskContext(request.getTaskId());
            AdviceTarget target = resolveTarget();
            // 流式只用轮询起点 Key（不重试），回调里据此记健康度；静态目标无 Key 不记
            AiApiKey streamKey = pickKey(target, 0);
            ChatClient chatClient = pickClient(target, 0);

            // 默认库策略：请求指定 → 配置项 → 首个启用库 → 无库（无制度依据降级）
            Long kbId = resolveKbId(request.getKbId());
            List<KbSearchResultDTO> hits = searchPolicy(kbId, taskContext);
            List<ApprovalReferenceVO> references = toReferences(hits);
            // 回调线程无 LoginContext：租户在请求线程快照
            LoginUser loginUser = LoginContext.get();
            Long tenantId = loginUser == null ? null : loginUser.getTenantId();

            // 0 = 不超时：长回复由模型流结束或异常驱动完成
            SseEmitter emitter = new SseEmitter(0L);
            Map<String, Object> metaData = new LinkedHashMap<>();
            metaData.put("taskId", taskContext.getTaskId());
            metaData.put("instanceId", taskContext.getInstanceId());
            metaData.put("kbId", kbId);
            metaData.put("disclaimer", DISCLAIMER);
            sendEvent(emitter, "meta", metaData);

            StringBuilder answer = new StringBuilder();
            // 流式已发 meta，失败不换 Key 重试（半途换 Key 会重复输出），直接下发 error 事件
            Flux<String> flux = buildPrompt(chatClient, target, buildUserPrompt(taskContext, hits))
                    .stream()
                    .content();
            flux.subscribe(
                    delta -> {
                        answer.append(delta);
                        sendEvent(emitter, "delta", Map.of("content", delta));
                    },
                    error -> {
                        log.error("[PivotOS] AI 审批建议生成失败：taskId={} keyId={}",
                                taskContext.getTaskId(), streamKey == null ? null : streamKey.getId(), error);
                        if (streamKey != null) {
                            aiProviderService.recordKeyFailure(streamKey.getId());
                        }
                        sendEvent(emitter, "error", Map.of(
                                "code", AiErrorCode.ADVICE_GEN_FAILED.getCode(),
                                "msg", AiErrorCode.ADVICE_GEN_FAILED.getMsg()));
                        emitter.complete();
                    },
                    () -> {
                        if (streamKey != null) {
                            aiProviderService.recordKeySuccess(streamKey.getId());
                        }
                        String raw = answer.toString();
                        AdviceParseResult parsed = parseAdvice(raw);
                        // 回调线程无 LoginContext，saveAdvice 内显式补齐审计字段
                        AiApprovalAdvice advice = saveAdvice(userId, tenantId, taskContext, kbId,
                                parsed, references, raw, target, startMs);
                        Map<String, Object> doneData = new LinkedHashMap<>();
                        doneData.put("adviceId", advice.getId());
                        doneData.put("conclusion", parsed.conclusion());
                        doneData.put("reason", parsed.reason());
                        doneData.put("references", references);
                        doneData.put("disclaimer", DISCLAIMER);
                        // A4E：前端据此决定是否发起受控自动预审（它只是「够格发起」的提示，
                        // 真正放行还要过 AutoApprovalPolicy 的全量规则，在审批人请求内判定）
                        doneData.put("autoEligible", autoApproveProperties.isEnabled()
                                && "approve".equals(parsed.conclusion())
                                && !references.isEmpty());
                        sendEvent(emitter, "done", doneData);
                        emitter.complete();
                    });
            return emitter;
        } finally {
            AiUsageContext.clear();
        }
    }

    @Override
    public ApprovalAdviceVO latestAdvice(Long userId, Long taskId) {
        AiApprovalAdvice advice = latestAdviceEntity(userId, taskId);
        return advice == null ? null : toAdviceVO(advice);
    }

    /** 本人在该任务上的最近一条建议——userId 过滤即归属闸：建议记录只对生成者本人可见 */
    private AiApprovalAdvice latestAdviceEntity(Long userId, Long taskId) {
        return adviceMapper.selectOne(Wrappers.<AiApprovalAdvice>lambdaQuery()
                .eq(AiApprovalAdvice::getTaskId, taskId)
                .eq(AiApprovalAdvice::getUserId, userId)
                .orderByDesc(AiApprovalAdvice::getId)
                .last("LIMIT 1"));
    }

    /* ================= A4E 受控自动预审（S117） ================= */

    /**
     * 受控自动预审：在<b>审批人本人的请求线程内</b>做确定性判定，规则全中则自动通过并留痕。
     *
     * <p><b>为什么必须由审批人本人发起请求</b>（这是本方法的架构前提，不是实现偷懒）：
     * 自动通过最终要走 {@code FlowTaskService.pass}，而它的归属闸来自 warm-flow 的
     * {@code PermissionHandler}——后者取的是 {@code LoginContext}。S117 开工实测：
     * 非审批人调 pass → 1500「无法跳转到该节点」；审批人调 → code=0。
     * 而 {@code LoginContext} 是只读 ScopedValue（无 runAs/bind），服务端无法代填身份，
     * 所以「后台扫描待办自动通过」在当前架构下不可实现，只能落在审批人的请求内。
     *
     * <p><b>为什么不能在 SSE 回调里顺手做掉</b>：流式回调在反应式线程执行，
     * {@code LoginContext} 已丢失（本类落库时要显式补审计字段就是因为这个），
     * 在那里调 pass 会因 handler 解析成 anonymous 而被拒。
     */
    @Override
    public AutoApprovalResultVO autoPass(Long userId, Long taskId) {
        // 归属闸第一步：非该任务审批人在这里就被拦下（workflow 侧 requireApprover 口径）
        ApprovalTaskContextDTO taskContext = loadTaskContext(taskId);
        AutoApprovalResultVO vo = new AutoApprovalResultVO();
        vo.setTaskId(taskId);
        AiApprovalAdvice advice = latestAdviceEntity(userId, taskId);
        if (advice == null) {
            vo.setAutoPassed(false);
            vo.setReason("本用户在该待办上尚无建议记录，无法自动预审");
            vo.setRuleHits(List.of());
            return vo;
        }
        vo.setAdviceId(advice.getId());
        List<ApprovalReferenceVO> references = parseReferences(advice.getReferencesJson());
        AutoApprovalPolicy.Decision decision = AutoApprovalPolicy.evaluate(new AutoApprovalPolicy.Input(
                autoApproveProperties.isEnabled(),
                autoApproveProperties.isRequirePolicyKb(),
                autoApproveProperties.isRequireSingleApprover(),
                advice.getConclusion(),
                references,
                isPolicyKb(advice.getKbId()),
                taskContext.getApproverCount(),
                taskContext.getFlowStatus(),
                taskContext.getVariables() == null ? Map.of() : taskContext.getVariables(),
                autoApproveProperties.getAmountVariableKey(),
                autoApproveProperties.getMaxAmount()));

        boolean passed = false;
        if (decision.pass()) {
            IWorkflowFacade workflowFacade = workflowFacadeProvider.getIfAvailable();
            if (workflowFacade == null) {
                throw new ServiceException(AiErrorCode.APPROVAL_TASK_NOT_FOUND);
            }
            workflowFacade.approveTask(taskId, autoApproveProperties.getMessage());
            passed = true;
        }
        // 留痕：无论通过与否都记，事后必须能回答「为什么这条被/没被自动通过」
        advice.setAutoPassed(passed ? 1 : 0);
        advice.setAutoDecisionReason(truncate(decision.reason(), 500));
        advice.setAutoRuleHits(JSON.toJSONString(decision.ruleHits()));
        adviceMapper.updateById(advice);

        vo.setAutoPassed(passed);
        vo.setReason(decision.reason());
        vo.setRuleHits(decision.ruleHits());
        log.info("[PivotOS] AI 受控自动预审：taskId={} adviceId={} passed={} hits={}",
                taskId, advice.getId(), passed, decision.ruleHits());
        return vo;
    }

    /** 引用 JSON → 引用列表（解析失败静默置空） */
    private List<ApprovalReferenceVO> parseReferences(String referencesJson) {
        if (referencesJson == null || referencesJson.isBlank()) {
            return List.of();
        }
        try {
            List<ApprovalReferenceVO> refs = JSON.parseArray(referencesJson, ApprovalReferenceVO.class);
            return refs == null ? List.of() : refs;
        } catch (Exception e) {
            log.warn("[PivotOS] 审批建议引用 JSON 解析失败，按无引用从严处理: {}", e.getMessage());
            return List.of();
        }
    }

    /* ================= 待办上下文聚合 ================= */

    /**
     * 取审批上下文：workflow 门面缺失按任务不存在处理（无审批任务可言）；
     * 归属闸在 workflow 侧（requireApprover 口径），非审批人异常按消息映射 5083
     * （错误码不跨插件传递，消息匹配为边界处约定）。
     */
    private ApprovalTaskContextDTO loadTaskContext(Long taskId) {
        IWorkflowFacade facade = workflowFacadeProvider.getIfAvailable();
        if (facade == null) {
            throw new ServiceException(AiErrorCode.APPROVAL_TASK_NOT_FOUND);
        }
        ApprovalTaskContextDTO ctx;
        try {
            ctx = facade.getApprovalTaskContext(taskId);
        } catch (ServiceException e) {
            if (e.getMessage() != null && e.getMessage().contains("审批人")) {
                throw new ServiceException(AiErrorCode.APPROVAL_NOT_APPROVER);
            }
            throw e;
        }
        if (ctx == null) {
            throw new ServiceException(AiErrorCode.APPROVAL_TASK_NOT_FOUND);
        }
        return ctx;
    }

    /* ================= 知识库检索 ================= */

    /**
     * 默认库策略：请求指定 > 配置项 pivotos.ai.approval.default-kb-id >
     * <b>listOptions 中首个制度类（kb_type=policy）库</b> > 首个启用库；均无返回 null（走无制度依据降级）。
     *
     * <p>为何把「优先制度类」插在「首个启用库」之前（A4E / S117）：制度类标记落地前，
     * 选库只能靠「建库顺序」，一个通用库被先建就会让审批建议拿通用资料当制度依据；
     * 现在 kb_type 是契约字段，制度类优先是确定性选择，不再依赖建库顺序。
     */
    private Long resolveKbId(Long requestedKbId) {
        if (requestedKbId != null) {
            return requestedKbId;
        }
        Long configured = environment.getProperty("pivotos.ai.approval.default-kb-id", Long.class);
        if (configured != null) {
            return configured;
        }
        IKnowledgeBaseFacade facade = kbFacadeProvider.getIfAvailable();
        if (facade == null) {
            return null;
        }
        try {
            List<KbOptionDTO> options = facade.listOptions();
            if (options.isEmpty()) {
                return null;
            }
            return options.stream()
                    .filter(option -> KbType.isPolicy(option.getKbType()))
                    .findFirst()
                    .orElse(options.get(0))
                    .getId();
        } catch (Exception e) {
            log.warn("[PivotOS] 审批建议加载知识库选项失败，走无制度依据降级: {}", e.getMessage());
            return null;
        }
    }

    /** 指定知识库是否为制度类（自动预审据此判定「依据是否来自制度库」） */
    private boolean isPolicyKb(Long kbId) {
        if (kbId == null) {
            return false;
        }
        IKnowledgeBaseFacade facade = kbFacadeProvider.getIfAvailable();
        if (facade == null) {
            return false;
        }
        try {
            return facade.listOptions().stream()
                    .anyMatch(option -> kbId.equals(option.getId()) && KbType.isPolicy(option.getKbType()));
        } catch (Exception e) {
            log.warn("[PivotOS] 审批建议判定知识库类型失败，按非制度类从严处理: kbId={}, error={}", kbId, e.getMessage());
            return false;
        }
    }

    /** 制度检索：无库/门面缺失/检索异常一律空结果降级（生成不断链，提示词走无资料分支） */
    private List<KbSearchResultDTO> searchPolicy(Long kbId, ApprovalTaskContextDTO taskContext) {
        if (kbId == null) {
            return List.of();
        }
        IKnowledgeBaseFacade facade = kbFacadeProvider.getIfAvailable();
        if (facade == null) {
            return List.of();
        }
        try {
            return facade.search(kbId, buildSearchQuery(taskContext), ADVICE_TOP_K);
        } catch (Exception e) {
            log.warn("[PivotOS] 审批建议制度检索失败，走无制度依据降级：kbId={}, error={}", kbId, e.getMessage());
            return List.of();
        }
    }

    /** 检索词：流程名 + 流程变量摘要（截断控长） */
    private String buildSearchQuery(ApprovalTaskContextDTO taskContext) {
        StringBuilder sb = new StringBuilder();
        if (taskContext.getFlowName() != null) {
            sb.append(taskContext.getFlowName()).append(' ');
        }
        taskContext.getVariables().forEach((k, v) -> {
            if (v != null) {
                sb.append(k).append('：').append(v).append('；');
            }
        });
        String query = sb.toString().strip();
        return query.length() > SEARCH_QUERY_MAX ? query.substring(0, SEARCH_QUERY_MAX) : query;
    }

    /** 检索片段 → 引用列表（服务端确定性构建：chunkId/fileName/quote） */
    private List<ApprovalReferenceVO> toReferences(List<KbSearchResultDTO> hits) {
        List<ApprovalReferenceVO> references = new ArrayList<>();
        for (KbSearchResultDTO hit : hits) {
            ApprovalReferenceVO ref = new ApprovalReferenceVO();
            ref.setChunkId(hit.getChunkId());
            ref.setFileName(hit.getFileName());
            ref.setQuote(hit.getContent() != null && hit.getContent().length() > QUOTE_MAX_LENGTH
                    ? hit.getContent().substring(0, QUOTE_MAX_LENGTH) + "…"
                    : hit.getContent());
            references.add(ref);
        }
        return references;
    }

    /* ================= Prompt 组装 ================= */

    /**
     * user prompt：审批任务上下文（实例信息 + 流程变量 + 审批历史）+ 制度参考资料（编号引用）。
     * 无资料时显式标注「未检索到相关制度」，配合 system 约束引导 need_info，不硬编结论。
     */
    private String buildUserPrompt(ApprovalTaskContextDTO taskContext, List<KbSearchResultDTO> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("【审批任务上下文】\n");
        appendLine(sb, "流程名称", taskContext.getFlowName());
        appendLine(sb, "当前节点", taskContext.getNodeName());
        appendLine(sb, "发起人", taskContext.getCreateBy());
        appendLine(sb, "发起时间", taskContext.getInstanceCreateTime());
        sb.append("流程变量：\n");
        if (taskContext.getVariables().isEmpty()) {
            sb.append("（无）\n");
        } else {
            taskContext.getVariables().forEach((k, v) -> {
                String value = v == null ? "" : String.valueOf(v);
                if (value.length() > VARIABLE_VALUE_MAX) {
                    value = value.substring(0, VARIABLE_VALUE_MAX) + "…";
                }
                sb.append("- ").append(k).append("：").append(value).append('\n');
            });
        }
        sb.append("审批历史：\n");
        if (taskContext.getHistory() == null || taskContext.getHistory().isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (ApprovalTaskContextDTO.HistoryItem his : taskContext.getHistory()) {
                sb.append("- ").append(his.getNodeName())
                        .append(" / 审批人：").append(his.getApprover())
                        .append(" / 动作：").append(his.getSkipType());
                if (his.getMessage() != null && !his.getMessage().isBlank()) {
                    sb.append(" / 意见：").append(his.getMessage());
                }
                sb.append('\n');
            }
        }
        sb.append("\n【制度参考资料】\n");
        if (hits.isEmpty()) {
            sb.append("未检索到相关制度，请据实输出 need_info。\n");
        } else {
            for (int i = 0; i < hits.size(); i++) {
                KbSearchResultDTO hit = hits.get(i);
                sb.append('[').append(i + 1).append("] ");
                if (hit.getFileName() != null) {
                    sb.append("来源：").append(hit.getFileName()).append('\n');
                }
                sb.append(hit.getContent()).append("\n\n");
            }
        }
        sb.append("请基于以上内容输出建议 JSON。");
        return sb.toString();
    }

    private void appendLine(StringBuilder sb, String label, Object value) {
        sb.append(label).append("：").append(value == null ? "（未知）" : value).append('\n');
    }

    /* ================= 模型调用目标解析（口径同对话链路，无请求级供应商/模型） ================= */

    /**
     * 解析调用目标（两级兜底链）：默认供应商（启用中 sort 最靠前且有启用 Key 者）→
     * 静态 ChatClient（spring.ai.openai.*）→ 5020。
     */
    private AdviceTarget resolveTarget() {
        AiProvider provider = aiProviderService.findDefaultProvider();
        if (provider != null) {
            List<AiApiKey> keys = aiProviderService.listActiveKeys(provider.getId());
            if (keys.isEmpty()) {
                throw new ServiceException(AiErrorCode.NO_AVAILABLE_KEY);
            }
            int startIndex = clientRegistry.nextKeyIndex(provider.getId(), keys.size());
            return new AdviceTarget(provider, keys, startIndex, provider.getDefaultModel(), null);
        }
        ChatClient staticClient = chatClientProvider.getIfAvailable();
        if (staticClient == null) {
            throw new ServiceException(AiErrorCode.AI_NOT_CONFIGURED);
        }
        return new AdviceTarget(null, null, 0,
                environment.getProperty("spring.ai.openai.chat.options.model", ""), staticClient);
    }

    /** 取第 attempt 次尝试对应的 Key（轮询偏移取模；静态目标无 Key 返回 null） */
    private AiApiKey pickKey(AdviceTarget target, int attempt) {
        if (!target.dynamic()) {
            return null;
        }
        return target.keys().get((target.startIndex() + attempt) % target.keys().size());
    }

    /** 取第 attempt 次尝试对应的 ChatClient（动态走轮询偏移，静态恒为兜底 client） */
    private ChatClient pickClient(AdviceTarget target, int attempt) {
        AiApiKey key = pickKey(target, attempt);
        if (key == null) {
            return target.staticClient();
        }
        return clientRegistry.getChatClient(target.provider(), key);
    }

    /** 组装 prompt：system 固化建议约束 + user 上下文；动态目标按供应商模型覆盖 options */
    private ChatClient.ChatClientRequestSpec buildPrompt(ChatClient client, AdviceTarget target, String userPrompt) {
        ChatClient.ChatClientRequestSpec spec = client.prompt()
                .system(ADVICE_SYSTEM_PROMPT)
                .user(userPrompt);
        if (target.dynamic() && target.model() != null && !target.model().isBlank()) {
            spec = spec.options(clientRegistry.buildChatOptions(target.provider(), target.model()));
        }
        return spec;
    }

    /** 调用目标：动态 = 供应商 + 启用 Key 列表 + 轮询起点；静态 = 兜底 ChatClient */
    private record AdviceTarget(AiProvider provider, List<AiApiKey> keys, int startIndex,
                                String model, ChatClient staticClient) {
        boolean dynamic() {
            return provider != null;
        }
    }

    /* ================= 结构化解析与落库 ================= */

    /** 解析结果：结论 + 理由（解析失败降级 need_info + 原文入 reason） */
    private record AdviceParseResult(String conclusion, String reason) {}

    /**
     * 解析模型输出为结构化建议：截取首个 { 到末个 } 的 JSON 片段（容忍代码围栏杂质）；
     * conclusion 非三态 / 字段缺失 / 解析异常一律降级 need_info + 原文入 reason，不断链。
     */
    private AdviceParseResult parseAdvice(String raw) {
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JSONObject json = JSON.parseObject(raw.substring(start, end + 1));
                String conclusion = json.getString("conclusion");
                if (conclusion != null && VALID_CONCLUSIONS.contains(conclusion)) {
                    String reason = json.getString("reason");
                    return new AdviceParseResult(conclusion, reason == null ? "" : reason);
                }
            }
        } catch (Exception e) {
            log.warn("[PivotOS] 审批建议 JSON 解析失败，降级 need_info: {}", e.getMessage());
        }
        return new AdviceParseResult("need_info", raw);
    }

    /** 建议落库：显式补齐审计字段（回调线程 LoginContext 丢失），防篡改存模型原文 */
    private AiApprovalAdvice saveAdvice(Long userId, Long tenantId, ApprovalTaskContextDTO taskContext,
                                        Long kbId, AdviceParseResult parsed,
                                        List<ApprovalReferenceVO> references, String raw,
                                        AdviceTarget target, long startMs) {
        LocalDateTime now = LocalDateTime.now();
        AiApprovalAdvice advice = new AiApprovalAdvice();
        advice.setTaskId(taskContext.getTaskId());
        advice.setInstanceId(taskContext.getInstanceId());
        advice.setUserId(userId);
        advice.setKbId(kbId);
        advice.setConclusion(parsed.conclusion());
        advice.setReason(parsed.reason());
        advice.setReferencesJson(references.isEmpty() ? null : JSON.toJSONString(references));
        advice.setRawContent(raw);
        advice.setProvider(target.provider() != null ? target.provider().getCode() : null);
        advice.setModel(target.model());
        advice.setCostMs(System.currentTimeMillis() - startMs);
        advice.setTenantId(tenantId);
        advice.setCreateBy(userId);
        advice.setUpdateBy(userId);
        advice.setCreateTime(now);
        advice.setUpdateTime(now);
        adviceMapper.insert(advice);
        return advice;
    }

    /** 建议回显：引用 JSON 反序列化回填（解析失败静默置空，不阻塞回显） */
    private ApprovalAdviceVO toAdviceVO(AiApprovalAdvice advice) {
        ApprovalAdviceVO vo = new ApprovalAdviceVO();
        vo.setId(advice.getId());
        vo.setTaskId(advice.getTaskId());
        vo.setConclusion(advice.getConclusion());
        vo.setReason(advice.getReason());
        vo.setKbId(advice.getKbId());
        vo.setCreateTime(advice.getCreateTime());
        vo.setAutoPassed(advice.getAutoPassed() != null && advice.getAutoPassed() == 1);
        vo.setAutoDecisionReason(advice.getAutoDecisionReason());
        List<ApprovalReferenceVO> references = parseReferences(advice.getReferencesJson());
        if (!references.isEmpty()) {
            vo.setReferences(references);
        }
        // 是否具备自动通过资格（轻量判定，不调 workflow）：开关开 + 结论通过 + 有制度依据。
        // 它只是「可以发起自动预审」的提示，真正放行还要过 AutoApprovalPolicy 全量规则。
        vo.setAutoEligible(autoApproveProperties.isEnabled()
                && "approve".equals(advice.getConclusion())
                && !references.isEmpty());
        return vo;
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    /** SSE 事件下发：data 走 JSON 转换器（换行安全），IO 异常说明客户端已断开 */
    private void sendEvent(SseEmitter emitter, String name, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(name)
                    .data(new LinkedHashMap<>(data), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("[PivotOS] SSE 客户端已断开，事件 {} 丢弃", name);
        }
    }
}
