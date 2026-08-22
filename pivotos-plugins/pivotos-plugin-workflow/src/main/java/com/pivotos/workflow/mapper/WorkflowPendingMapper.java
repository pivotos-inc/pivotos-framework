package com.pivotos.workflow.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.warm.flow.orm.entity.FlowTask;

/**
 * 待办任务归属查询 Mapper（S93）。
 * <p>
 * warm-flow 1.8.7 的 taskService.page 是纯实体条件分页，不做 PermissionHandler 权限过滤，
 * 任何登录用户会看到全部待办；故在服务层以 flow_user 归属（审批/转办/委派三类办理人）
 * 子查询过滤，分页与计数同口径。任务办结后引擎删除 flow_user 记录，口径自洽。
 */
@Mapper
public interface WorkflowPendingMapper {

    /** 归属过滤条件片段（processedBy=当前用户，type：1 审批 / 2 转办 / 3 委派） */
    String OWNER_FILTER = "t.id IN (SELECT DISTINCT fu.associated FROM flow_user fu "
            + "WHERE fu.processed_by = #{processedBy} AND fu.type IN ('1','2','3') AND fu.del_flag = '0')";

    @Select("<script>"
            + "SELECT t.* FROM flow_task t "
            + "WHERE t.del_flag = '0' AND t.flow_status = '1' AND " + OWNER_FILTER
            + "<if test='flowName != null and flowName != \"\"'> AND t.flow_name LIKE CONCAT('%', #{flowName}, '%') </if>"
            + "ORDER BY t.create_time DESC"
            + "</script>")
    IPage<FlowTask> selectPendingPage(IPage<FlowTask> page,
                                      @Param("processedBy") String processedBy,
                                      @Param("flowName") String flowName);

    @Select("<script>"
            + "SELECT COUNT(*) FROM flow_task t "
            + "WHERE t.del_flag = '0' AND t.flow_status = '1' AND " + OWNER_FILTER
            + "<if test='flowName != null and flowName != \"\"'> AND t.flow_name LIKE CONCAT('%', #{flowName}, '%') </if>"
            + "</script>")
    long countPending(@Param("processedBy") String processedBy,
                      @Param("flowName") String flowName);
}
