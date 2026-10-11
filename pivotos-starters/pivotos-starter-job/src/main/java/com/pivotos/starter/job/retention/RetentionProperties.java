package com.pivotos.starter.job.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据留存策略配置（{@code pivotos.retention.*}）。
 * <p>
 * 背景（v3.1.0 生产化 P1-B1）：日志 / 流水类表只增不改，没有清理作业时体积无限增长。
 * 本配置是「保留窗口 + 安全带」的唯一出处，各插件的清理任务只读这里，不在自己模块里另立口径。
 *
 * <h3>四道安全带（缺一不可）</h3>
 * <ol>
 *   <li><b>硬下限</b>：{@code min-retention-days}（默认 1）。任何表配到低于它的保留天数，
 *       任务<strong>拒绝执行并抛异常</strong>，而不是「按配置删」——配错成 0 天等于全表清空，
 *       这种错误必须响，不能静默。</li>
 *   <li><b>单次上限</b>：{@code max-delete-per-run}（默认 5000）。一轮最多删这么多行，
 *       删不完下一轮继续。目的：避免首次启用时一次性删掉百万行把主库拖垮（大事务 + 主从延迟）。</li>
 *   <li><b>dry-run</b>：{@code dry-run=true} 时只统计不删，用于上线前试算「这一刀下去会删多少」。</li>
 *   <li><b>总开关</b>：{@code enabled=false} 时所有留存任务直接跳过（返回 skipped 报告）。</li>
 * </ol>
 *
 * <h3>为什么默认是物理删除</h3>
 * 本仓 {@code BaseDO} 的 {@code deleted} 带 {@code @TableLogic}，MP 的 {@code delete()} 会被改写成
 * {@code UPDATE SET deleted=1}——<strong>行数不变、空间不回收</strong>，留存策略会形同虚设。
 * 所以各清理任务的 Mapper 走显式 {@code DELETE} 注解（物理删除），这也是为什么安全带必须做厚。
 * 已逻辑删除的历史行（{@code deleted=1}）同样在清理范围内：它们一样占空间。
 *
 * @author PivotOS
 * @since 3.0.0
 */
@ConfigurationProperties(prefix = "pivotos.retention")
public class RetentionProperties {

    /** 留存清理总开关：false 时所有任务跳过（默认 true，策略要生效） */
    private boolean enabled = true;

    /** 演练模式：true 时只统计待删行数、不执行删除 */
    private boolean dryRun = false;

    /** 单表单租户的单次删除上限（行数），防止首次启用时一次删爆主库 */
    private int maxDeletePerRun = 5000;

    /**
     * 保留天数硬下限：任何表配置低于此值一律拒绝执行并抛异常。
     * <p>这条不是「建议值」而是红线——保留 0 天的语义是全表清空。
     */
    private int minRetentionDays = 1;

    /** 登录日志 sys_login_log 保留天数 */
    private int loginLogDays = 180;

    /** 任务执行日志 sys_job_log 保留天数 */
    private int jobLogDays = 180;

    /** 操作日志 sys_oper_log 保留天数（与既有 pivotos.job.oper-log-retention-days 双向兼容） */
    private int operLogDays = 180;

    /** 消息三表 msg_message / msg_user_message / msg_send_log 保留天数 */
    private int messageDays = 180;

    /** AI 会话消息 ai_chat_message 保留天数 */
    private int aiChatDays = 180;

    /** AI 用量 ai_usage 保留天数（计费/对账依据，默认比会话更长） */
    private int aiUsageDays = 365;

    /** AI 工具调用 ai_tool_invoke 保留天数 */
    private int aiToolInvokeDays = 180;

    /** 流程历史任务 flow_his_task 保留天数（审批留痕依据，默认最长） */
    private int flowHisTaskDays = 365;

    /** 本地调度兜底（XXL-Job 不可达时仍执行） */
    private LocalFallback localFallback = new LocalFallback();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public int getMaxDeletePerRun() {
        return maxDeletePerRun;
    }

    public void setMaxDeletePerRun(int maxDeletePerRun) {
        this.maxDeletePerRun = maxDeletePerRun;
    }

    public int getMinRetentionDays() {
        return minRetentionDays;
    }

    public void setMinRetentionDays(int minRetentionDays) {
        this.minRetentionDays = minRetentionDays;
    }

    public int getLoginLogDays() {
        return loginLogDays;
    }

    public void setLoginLogDays(int loginLogDays) {
        this.loginLogDays = loginLogDays;
    }

    public int getJobLogDays() {
        return jobLogDays;
    }

    public void setJobLogDays(int jobLogDays) {
        this.jobLogDays = jobLogDays;
    }

    public int getOperLogDays() {
        return operLogDays;
    }

    public void setOperLogDays(int operLogDays) {
        this.operLogDays = operLogDays;
    }

    public int getMessageDays() {
        return messageDays;
    }

    public void setMessageDays(int messageDays) {
        this.messageDays = messageDays;
    }

    public int getAiChatDays() {
        return aiChatDays;
    }

    public void setAiChatDays(int aiChatDays) {
        this.aiChatDays = aiChatDays;
    }

    public int getAiUsageDays() {
        return aiUsageDays;
    }

    public void setAiUsageDays(int aiUsageDays) {
        this.aiUsageDays = aiUsageDays;
    }

    public int getAiToolInvokeDays() {
        return aiToolInvokeDays;
    }

    public void setAiToolInvokeDays(int aiToolInvokeDays) {
        this.aiToolInvokeDays = aiToolInvokeDays;
    }

    public int getFlowHisTaskDays() {
        return flowHisTaskDays;
    }

    public void setFlowHisTaskDays(int flowHisTaskDays) {
        this.flowHisTaskDays = flowHisTaskDays;
    }

    public LocalFallback getLocalFallback() {
        return localFallback;
    }

    public void setLocalFallback(LocalFallback localFallback) {
        this.localFallback = localFallback;
    }

    /**
     * 本地调度兜底配置。
     * <p><b>为什么不与 XXL-Job 并存</b>：{@code LocalRetentionScheduler} 以
     * {@code @ConditionalOnMissingBean(XxlJobSpringExecutor.class)} 守卫，XXL-Job 装配了本地调度
     * 就不注册——从类型系统上杜绝「两边各跑一次」的双跑，而不是靠配置纪律。
     */
    public static class LocalFallback {

        /** 本地兜底开关（默认开；XXL-Job 生效时本项无意义） */
        private boolean enabled = true;

        /**
         * 本地兜底 cron，默认每天 03:00（Spring 六位：秒 分 时 日 月 周）。
         * <p>刻意避开 02:00（与既有操作日志清理的推荐 cron 错峰），且留足备份窗口。
         */
        private String cron = "0 0 3 * * *";

        /**
         * 强制启用本地兜底：<strong>即使调度中心已配置也接管</strong>（默认 false）。
         * <p>
         * 用于「{@code pivotos.job.admin-addresses} 配了，但调度中心实际不可达」这一形态：
         * 默认条件只看 XxlJobSpringExecutor 是否装配，配了就不接管，
         * 于是中心挂掉时留存清理会静默停止且无任何信号。
         * 确认中心长期不可达时置 true（需重启）。
         * <p>
         * <b>代价</b>：若中心其实可达，清理会被跑两遍（按主键幂等，结果正确，执行记录多一条）。
         */
        private boolean force = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public boolean isForce() {
            return force;
        }

        public void setForce(boolean force) {
            this.force = force;
        }
    }
}
