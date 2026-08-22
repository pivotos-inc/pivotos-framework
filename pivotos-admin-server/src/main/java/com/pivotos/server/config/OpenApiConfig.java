package com.pivotos.server.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * SpringDoc / Knife4j OpenAPI 全局配置
 *
 * <p>设置 API 文档的标题、描述、版本号、联系人以及全局 Sa-Token Bearer 认证方案，
 * 使导出的 OpenAPI JSON 在 Apifox / Torna / ShowDoc 等平台中显示完整中文元数据。
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "Bearer";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("PivotOS 企业级应用开发平台 API")
                        .description("PivotOS — 一码三端企业级应用开发平台，涵盖系统管理、AI 对话、知识库、工作流、代码生成等模块的 RESTful 接口文档。")
                        .version("V2.10.0")
                        .contact(new Contact()
                                .name("PivotOS")
                                .url("https://github.com/pivotos/pivotos")
                                .email("dev@pivotos.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("Sa-Token")
                                .in(SecurityScheme.In.HEADER)
                                .name("Authorization")));
    }

    /**
     * 文档汉化与元数据补全定制器：
     * <ul>
     *   <li>warm-flow 第三方引擎 Controller 无法修改源码，重写其 tag 与 summary 为中文；</li>
     *   <li>统一为分页参数 pageNum/pageSize 补充中文描述（commons-core 不引入 swagger 依赖）。</li>
     * </ul>
     */
    @Bean
    public GlobalOpenApiCustomizer openApiChineseCustomizer() {
        Map<String, String> tagRename = Map.of(
                "warm-flow-controller", "流程引擎",
                "warm-flow-ui-controller", "流程设计器",
                "encrypt-key-controller", "加密握手");
        Map<String, String> summaryMap = Map.ofEntries(
                Map.entry("WarmFlowController_saveJson", "保存流程定义（JSON）"),
                Map.entry("WarmFlowController_saveFormContent", "保存节点表单内容"),
                Map.entry("WarmFlowController_handle", "执行流程任务"),
                Map.entry("WarmFlowController_queryFlowChart", "查询流程图"),
                Map.entry("WarmFlowController_queryDef", "流程定义详情"),
                Map.entry("WarmFlowController_queryDef_1", "流程定义列表"),
                Map.entry("WarmFlowController_publishedForm", "已发布节点表单"),
                Map.entry("WarmFlowController_nodeExt", "节点扩展属性"),
                Map.entry("WarmFlowController_listenerList", "监听器列表"),
                Map.entry("WarmFlowController_handlerType", "处理器类型列表"),
                Map.entry("WarmFlowController_handlerResult", "处理器执行结果"),
                Map.entry("WarmFlowController_handlerFeedback", "处理器反馈信息"),
                Map.entry("WarmFlowController_handlerDict", "处理器字典"),
                Map.entry("WarmFlowController_getFormContent", "节点表单内容"),
                Map.entry("WarmFlowController_load", "加载当前任务"),
                Map.entry("WarmFlowController_hisLoad", "加载历史任务"),
                Map.entry("WarmFlowUiController_config", "设计器配置"),
                Map.entry("EncryptKeyController_publicKey", "获取RSA公钥"));
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> item.readOperations().forEach(op -> {
                // tag 汉化
                if (op.getTags() != null) {
                    op.setTags(op.getTags().stream()
                            .map(t -> tagRename.getOrDefault(t, t))
                            .toList());
                }
                // summary 补全
                if ((op.getSummary() == null || op.getSummary().isBlank()) && op.getOperationId() != null) {
                    String cn = summaryMap.get(op.getOperationId());
                    if (cn != null) {
                        op.setSummary(cn);
                    }
                }
                // 分页参数描述补全
                if (op.getParameters() != null) {
                    op.getParameters().forEach(p -> {
                        if (p.getDescription() == null || p.getDescription().isBlank()) {
                            if ("pageNum".equals(p.getName())) {
                                p.setDescription("页码，从 1 开始");
                            } else if ("pageSize".equals(p.getName())) {
                                p.setDescription("每页条数（最大 500）");
                            }
                        }
                    });
                }
            }));
            // 顶层 tags 补充中文描述
            List<Tag> tags = openApi.getTags() != null ? openApi.getTags() : new java.util.ArrayList<>();
            if (tags.stream().noneMatch(t -> "流程引擎".equals(t.getName()))) {
                tags.add(new Tag().name("流程引擎").description("WarmFlow 流程引擎内置接口（定义/表单/任务执行）"));
            }
            if (tags.stream().noneMatch(t -> "流程设计器".equals(t.getName()))) {
                tags.add(new Tag().name("流程设计器").description("WarmFlow 设计器配置接口"));
            }
            if (tags.stream().noneMatch(t -> "加密握手".equals(t.getName()))) {
                tags.add(new Tag().name("加密握手").description("签发 RSA 公钥，建立接口加解密会话"));
            }
            openApi.setTags(tags);
        };
    }
}
