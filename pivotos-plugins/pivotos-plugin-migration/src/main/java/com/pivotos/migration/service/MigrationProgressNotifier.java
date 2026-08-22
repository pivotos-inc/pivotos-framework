package com.pivotos.migration.service;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 迁移进度 SSE 推送器（技术方案 §9.3）。
 *
 * <p>按任务维度维护 SseEmitter 连接池：前端以 POST 建立进度流（EventSource 无法携带
 * Authorization 头，与 AI 对话流式端点同款约定），业务链路在关键节点调用
 * {@link #publish(Long, String, Map)} 广播进度事件。</p>
 *
 * <p>事件载荷统一包含 eventType/taskId/timestamp，业务字段（stepId/stepName/status/
 * progressPercent/message）由各埋点按需提供。</p>
 */
@Slf4j
@Component
public class MigrationProgressNotifier {

    /** taskId → 该任务的活跃 SSE 连接列表 */
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * 订阅指定任务的进度流。
     *
     * @param taskId 迁移任务 ID
     * @return SseEmitter（无超时，由业务完成或客户端断开驱动关闭）
     */
    public SseEmitter subscribe(Long taskId) {
        SseEmitter emitter = new SseEmitter(0L);
        CopyOnWriteArrayList<SseEmitter> list = emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable cleanup = () -> removeEmitter(taskId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("message", "进度流已建立");
        hello.put("status", "CONNECTED");
        sendEvent(emitter, taskId, "CONNECTED", hello);
        log.info("迁移进度流已订阅，taskId={}, 当前连接数={}", taskId, list.size());
        return emitter;
    }

    /**
     * 广播进度事件（业务线程同步调用；发送失败的连接自动剔除，不阻断业务）。
     *
     * @param taskId    迁移任务 ID
     * @param eventType 事件类型（PARSE/ANALYZE/PLAN/STEP_PROGRESS/REVIEW/APPLY 等）
     * @param payload   业务载荷（stepId/stepName/status/progressPercent/message 等）
     */
    public void publish(Long taskId, String eventType, Map<String, Object> payload) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list == null || list.isEmpty()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>(payload);
        body.put("eventType", eventType);
        body.put("taskId", taskId);
        body.put("timestamp", System.currentTimeMillis());
        for (SseEmitter emitter : list) {
            sendEvent(emitter, taskId, eventType, body);
        }
    }

    /** 单个连接发送；异常即视为客户端已断开，剔除连接 */
    private void sendEvent(SseEmitter emitter, Long taskId, String eventType, Map<String, Object> body) {
        try {
            emitter.send(SseEmitter.event().name(eventType).data(JSON.toJSONString(body)));
        } catch (Exception e) {
            log.debug("迁移进度 SSE 发送失败（连接已断开），taskId={}, eventType={}", taskId, eventType);
            removeEmitter(taskId, emitter);
        }
    }

    private void removeEmitter(Long taskId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(taskId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(taskId, list);
            }
        }
    }
}
