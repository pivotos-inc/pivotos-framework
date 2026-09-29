package com.pivotos.workflow.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W1（S113）待办口径契约锁。
 * <p>
 * 口径改动（{@code flow_status = '1'} → {@code IN ('1','9')}）是「一处改动、多面受影响」的中枢，
 * 而 SQL 写在注解里、编译期无约束——改回单状态值不会有任何编译或运行报错，只会静默让退回任务重新变成死单。
 * 故用反射读 {@code @Select} 注解值做契约断言，把口径钉死在测试里。
 * <p>
 * 同时断言归属过滤条件（{@code flow_user} 子查询）仍在——W1 只放宽状态，不放宽归属。
 */
class WorkflowPendingMapperTest {

    private static String sqlOf(String methodName) throws NoSuchMethodException {
        Method m = WorkflowPendingMapper.class.getMethod(methodName,
                com.baomidou.mybatisplus.core.metadata.IPage.class, String.class, String.class);
        Select ann = m.getAnnotation(Select.class);
        assertThat(ann).as("%s 必须保留 @Select 注解（口径断言依赖）", methodName).isNotNull();
        return String.join("", ann.value());
    }

    private static String countSql() throws NoSuchMethodException {
        Method m = WorkflowPendingMapper.class.getMethod("countPending", String.class, String.class);
        Select ann = m.getAnnotation(Select.class);
        assertThat(ann).as("countPending 必须保留 @Select 注解").isNotNull();
        return String.join("", ann.value());
    }

    @Test
    @DisplayName("W1：待办分页与计数口径必须同时包含审批中(1)与已退回(9)")
    void pendingSqlMustIncludeBothApprovalAndRejectedStatus() throws Exception {
        String pageSql = sqlOf("selectPendingPage");
        String cntSql = countSql();
        assertThat(pageSql).contains("flow_status IN ('1','9')");
        assertThat(cntSql).contains("flow_status IN ('1','9')");
        // 不允许回退成单状态口径
        assertThat(pageSql).doesNotContain("flow_status = '1'");
        assertThat(cntSql).doesNotContain("flow_status = '1'");
    }

    @Test
    @DisplayName("W1：状态放宽但归属过滤不得放松（flow_user 子查询仍在）")
    void ownerFilterMustRemain() throws Exception {
        assertThat(sqlOf("selectPendingPage")).contains(WorkflowPendingMapper.OWNER_FILTER);
        assertThat(countSql()).contains(WorkflowPendingMapper.OWNER_FILTER);
        // 归属口径：审批(1)/转办(2)/委派(3) 三类办理人，且排除已逻辑删除
        assertThat(WorkflowPendingMapper.OWNER_FILTER)
                .contains("flow_user")
                .contains("processed_by")
                .contains("'1','2','3'")
                .contains("del_flag = '0'");
    }
}
