package com.pivotos.starter.cloud.api.context;

import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TenantContext;
import com.pivotos.starter.core.context.TraceContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文传播编解码器测试：三通道共用的这一份实现，是「身份/租户不丢」的唯一保障。
 */
class CloudContextCodecTest {

    @Test
    void capture_reads_from_scoped_value_contexts() {
        CloudContext empty = CloudContextCodec.capture();
        assertThat(empty.isEmpty()).as("未绑定任何上下文时必须为空，不能凭空造出身份").isTrue();

        ScopedValue.where(TenantContext.KEY, 7L).run(() -> {
            ScopedValue.where(LoginContext.KEY, new LoginUser(1001L, "alice", "sys-user", 7L)).run(() -> {
                ScopedValue.where(TraceContext.KEY, "trace-1").run(() -> {
                    CloudContext ctx = CloudContextCodec.capture();
                    assertThat(ctx.getTenantId()).isEqualTo(7L);
                    assertThat(ctx.getUserId()).isEqualTo(1001L);
                    assertThat(ctx.getUsername()).isEqualTo("alice");
                    assertThat(ctx.getAccountType()).isEqualTo("sys-user");
                    assertThat(ctx.getTraceId()).isEqualTo("trace-1");
                });
            });
        });
    }

    @Test
    void headers_round_trip() {
        CloudContext ctx = new CloudContext(9L, 42L, "bob", "app-user", "trace-9");
        Map<String, String> headers = CloudContextCodec.toHeaders(ctx);
        assertThat(headers).containsEntry(CloudHeaders.TENANT_ID, "9")
            .containsEntry(CloudHeaders.USER_ID, "42")
            .containsEntry(CloudHeaders.USERNAME, "bob")
            .containsEntry(CloudHeaders.ACCOUNT_TYPE, "app-user")
            .containsEntry(CloudHeaders.TRACE_ID, "trace-9");

        Map<String, String> lookup = new HashMap<>(headers);
        CloudContext parsed = CloudContextCodec.fromHeaders(lookup::get);
        assertThat(parsed).as("请求头往返后必须与快照一致，否则跨进程传播会丢字段").isEqualTo(ctx);
    }

    @Test
    void to_headers_skips_null_fields() {
        Map<String, String> headers = CloudContextCodec.toHeaders(CloudContext.ofTenant(3L));
        assertThat(headers).hasSize(1).containsEntry(CloudHeaders.TENANT_ID, "3");
        assertThat(CloudContextCodec.toHeaders(null)).isEmpty();
    }

    @Test
    void from_request_parses_headers() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CloudHeaders.TENANT_ID, "11");
        request.addHeader(CloudHeaders.USERNAME, "carol");
        CloudContext ctx = CloudContextCodec.fromRequest(request);
        assertThat(ctx.getTenantId()).isEqualTo(11L);
        assertThat(ctx.getUsername()).isEqualTo("carol");
        assertThat(ctx.getUserId()).isNull();
    }

    @Test
    void run_with_binds_only_non_null_parts() {
        CloudContext tenantOnly = CloudContext.ofTenant(21L);
        ScopedValue.where(TenantContext.KEY, 1L).run(() -> {
            CloudContextCodec.runWith(tenantOnly, () -> {
                assertThat(TenantContext.get()).as("局部恢复必须覆盖为上游值").isEqualTo(21L);
                assertThat(LoginContext.isLogin()).as("快照里没有身份时绝不凭空造出登录态").isFalse();
            });
        });

        CloudContext full = new CloudContext(5L, 88L, "dave", "sys-user", null);
        CloudContextCodec.runWith(full, () -> {
            assertThat(TenantContext.get()).isEqualTo(5L);
            assertThat(LoginContext.getUserId()).isEqualTo(88L);
            assertThat(LoginContext.getUsername()).isEqualTo("dave");
        });
    }

    @Test
    void run_with_empty_context_runs_task() {
        boolean[] ran = {false};
        CloudContextCodec.runWith(CloudContext.EMPTY, () -> ran[0] = true);
        assertThat(ran[0]).isTrue();
    }

    @Test
    void internal_call_check_is_strict_by_default() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CloudHeaders.INTERNAL_TOKEN, "secret");
        assertThat(CloudContextCodec.isInternalCall(request, null)).as("未配凭证必须拒绝").isFalse();
        assertThat(CloudContextCodec.isInternalCall(request, "")).as("空凭证必须拒绝").isFalse();
        assertThat(CloudContextCodec.isInternalCall(request, "wrong")).isFalse();

        MockHttpServletRequest noHeader = new MockHttpServletRequest();
        assertThat(CloudContextCodec.isInternalCall(noHeader, "secret")).as("请求未带头必须拒绝").isFalse();

        assertThat(CloudContextCodec.isInternalCall(request, "secret")).isTrue();
    }
}
