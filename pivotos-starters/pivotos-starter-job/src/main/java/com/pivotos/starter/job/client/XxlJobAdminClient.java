package com.pivotos.starter.job.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pivotos.starter.job.config.JobProperties;
import com.xxl.job.core.constant.Const;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * XXL-Job admin Open API 客户端。
 * <p>
 * 通过 Token + Appname 认证调用调度中心的 Open API（{@code /api/{uri}}），
 * 管理任务的增删改查、启停与手动触发。
 * <p>
 * 请求体以 Gson JsonObject 手动构建，不依赖 fork 的 DTO 类（Maven Central 版本不含 openapi.admin.dto 包）。
 * <p>
 * 调度中心不可达时返回 {@link XxlJobResult#fail(String)}，不抛异常。
 *
 * @author PivotOS
 * @since 2.10.0
 */
public class XxlJobAdminClient {

    private static final Logger log = LoggerFactory.getLogger(XxlJobAdminClient.class);

    private static final int RESPONSE_CODE_200 = 200;

    /** Maven Central 版本 Const 无此常量，fork 版本值为 "XXL-JOB-APPNAME" */
    private static final String HEADER_APPNAME = "XXL-JOB-APPNAME";

    private final String adminAddresses;
    private final String accessToken;
    private final String appName;
    private final int groupId;
    private final HttpClient httpClient;

    public XxlJobAdminClient(JobProperties properties) {
        this.adminAddresses = properties.getAdminAddresses().replaceAll("/+$", "");
        this.accessToken = properties.getAccessToken();
        this.appName = properties.getAppName();
        this.groupId = properties.getGroupId();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        log.info("[PivotOS] XxlJobAdminClient initialized: admin={}, appname={}, groupId={}",
                adminAddresses, appName, groupId);
    }

    /**
     * 新增任务到调度中心。
     *
     * @param jobName            任务显示名
     * @param executorHandler    handler 名
     * @param executorParam      任务参数
     * @param scheduleType       调度类型 NONE/CRON/FIX_RATE
     * @param scheduleConf       调度配置
     * @param misfireStrategy    过期策略
     * @param executorRouteStrategy  路由策略
     * @param executorBlockStrategy  阻塞策略
     * @param executorTimeout       超时秒数
     * @param executorFailRetryCount 重试次数
     * @return 成功返回 jobId，失败返回 fail(msg)
     */
    public XxlJobResult addJob(String jobName, String executorHandler, String executorParam,
                               String scheduleType, String scheduleConf,
                               String misfireStrategy, String executorRouteStrategy,
                               String executorBlockStrategy, int executorTimeout,
                               int executorFailRetryCount) {
        JsonObject body = new JsonObject();
        body.addProperty("jobGroup", groupId);
        body.addProperty("name", jobName);
        body.addProperty("author", "PivotOS");
        body.addProperty("scheduleType", scheduleType);
        if (scheduleConf != null) {
            body.addProperty("scheduleConf", scheduleConf);
        }
        body.addProperty("misfireStrategy", misfireStrategy);
        body.addProperty("executorRouteStrategy", executorRouteStrategy);
        body.addProperty("executorHandler", executorHandler);
        body.addProperty("executorParam", executorParam != null ? executorParam : "");
        body.addProperty("executorBlockStrategy", executorBlockStrategy);
        body.addProperty("executorTimeout", executorTimeout);
        body.addProperty("executorFailRetryCount", executorFailRetryCount);
        body.addProperty("glueType", "BEAN");
        return post("addJob", body);
    }

    /**
     * 更新调度中心任务。
     */
    public XxlJobResult updateJob(int xxlJobId, String jobName, String executorHandler,
                                  String executorParam, String scheduleType, String scheduleConf,
                                  String misfireStrategy, String executorRouteStrategy,
                                  String executorBlockStrategy, int executorTimeout,
                                  int executorFailRetryCount) {
        JsonObject body = new JsonObject();
        body.addProperty("id", xxlJobId);
        body.addProperty("name", jobName);
        body.addProperty("author", "PivotOS");
        body.addProperty("scheduleType", scheduleType);
        if (scheduleConf != null) {
            body.addProperty("scheduleConf", scheduleConf);
        }
        body.addProperty("misfireStrategy", misfireStrategy);
        body.addProperty("executorRouteStrategy", executorRouteStrategy);
        body.addProperty("executorHandler", executorHandler);
        body.addProperty("executorParam", executorParam != null ? executorParam : "");
        body.addProperty("executorBlockStrategy", executorBlockStrategy);
        body.addProperty("executorTimeout", executorTimeout);
        body.addProperty("executorFailRetryCount", executorFailRetryCount);
        body.addProperty("glueType", "BEAN");
        return post("updateJob", body);
    }

    /**
     * 从调度中心删除任务。
     */
    public XxlJobResult removeJob(int jobId) {
        JsonObject body = new JsonObject();
        body.addProperty("id", jobId);
        return post("removeJob", body);
    }

    /**
     * 启动调度。
     */
    public XxlJobResult startJob(int jobId) {
        JsonObject body = new JsonObject();
        body.addProperty("id", jobId);
        return post("startJob", body);
    }

    /**
     * 暂停调度。
     */
    public XxlJobResult stopJob(int jobId) {
        JsonObject body = new JsonObject();
        body.addProperty("id", jobId);
        return post("stopJob", body);
    }

    /**
     * 手动触发一次执行。
     */
    public XxlJobResult triggerJob(int jobId, String executorParam) {
        JsonObject body = new JsonObject();
        body.addProperty("id", jobId);
        body.addProperty("executorParam", executorParam != null ? executorParam : "");
        body.add("addressList", JsonNull.INSTANCE);
        return post("triggerJob", body);
    }

    // ---------- pageList ----------

    /**
     * 分页查询调度中心任务列表。
     *
     * @param offset        分页偏移量（0-based）
     * @param pagesize      每页数量
     * @param name          任务名称（模糊查询，null 或空字符串表示不筛选）
     * @param triggerStatus 调度状态：-1 全部 0 停止 1 运行
     * @return 成功返回 {@link XxlJobPageResult#ok}，失败返回 {@link XxlJobPageResult#fail}
     */
    public XxlJobPageResult pageListJob(int offset, int pagesize, String name, int triggerStatus) {
        JsonObject body = new JsonObject();
        body.addProperty("offset", offset);
        body.addProperty("pagesize", pagesize);
        body.addProperty("triggerStatus", triggerStatus);
        body.addProperty("name", name != null ? name : "");
        body.addProperty("executorHandler", "");
        return postForPage("pageListJob", body);
    }

    // ---------- internal ----------

    private XxlJobResult post(String uri, JsonObject body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(adminAddresses + "/api/" + uri))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header(Const.XXL_JOB_ACCESS_TOKEN, accessToken != null ? accessToken : "")
                    .header(HEADER_APPNAME, appName)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[PivotOS] XXL-Job {} HTTP {} - {}", uri, response.statusCode(), response.body());
                return XxlJobResult.fail("HTTP " + response.statusCode());
            }

            return parseResponse(response.body(), uri);
        } catch (Exception e) {
            log.warn("[PivotOS] XXL-Job {} failed: {}", uri, e.getMessage());
            return XxlJobResult.fail(e.getMessage());
        }
    }

    private XxlJobResult parseResponse(String body, String operation) {
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        int code = json.get("code").getAsInt();
        String msg = json.has("msg") && !json.get("msg").isJsonNull()
                ? json.get("msg").getAsString() : null;

        if (code != RESPONSE_CODE_200) {
            return XxlJobResult.fail(msg != null ? msg : "XXL-Job " + operation + " failed (code=" + code + ")");
        }

        Integer jobId = null;
        if (json.has("data") && !json.get("data").isJsonNull()) {
            try {
                jobId = json.get("data").getAsInt();
            } catch (Exception ignored) {
                // data 不是数字，忽略
            }
        }

        return jobId != null ? XxlJobResult.ok(jobId) : XxlJobResult.ok();
    }

    private XxlJobPageResult postForPage(String uri, JsonObject body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(adminAddresses + "/api/" + uri))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header(Const.XXL_JOB_ACCESS_TOKEN, accessToken != null ? accessToken : "")
                    .header(HEADER_APPNAME, appName)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[PivotOS] XXL-Job {} HTTP {} - {}", uri, response.statusCode(), response.body());
                return XxlJobPageResult.fail("HTTP " + response.statusCode());
            }

            return parsePageResponse(response.body());
        } catch (Exception e) {
            log.warn("[PivotOS] XXL-Job {} failed: {}", uri, e.getMessage());
            return XxlJobPageResult.fail(e.getMessage());
        }
    }

    private XxlJobPageResult parsePageResponse(String body) {
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        int code = json.get("code").getAsInt();

        if (code != RESPONSE_CODE_200) {
            String msg = json.has("msg") && !json.get("msg").isJsonNull()
                    ? json.get("msg").getAsString() : null;
            return XxlJobPageResult.fail(msg != null ? msg : "XXL-Job pageList failed (code=" + code + ")");
        }

        // Response<PageModel<XxlJobInfo>> 结构: data.data (数组) + data.total (int)
        List<JsonObject> list = new ArrayList<>();
        int total = 0;

        if (json.has("data") && !json.get("data").isJsonNull()) {
            JsonObject pageData = json.getAsJsonObject("data");

            if (pageData.has("total") && !pageData.get("total").isJsonNull()) {
                total = pageData.get("total").getAsInt();
            }

            if (pageData.has("data") && !pageData.get("data").isJsonNull()) {
                JsonArray dataArray = pageData.getAsJsonArray("data");
                for (JsonElement element : dataArray) {
                    list.add(element.getAsJsonObject());
                }
            }
        }

        return XxlJobPageResult.ok(list, total);
    }
}
