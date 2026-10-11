package com.pivotos.server.config;

import com.baomidou.dynamic.datasource.creator.druid.DruidConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接池（Druid）生产参数守卫（V3.1-G · P1-B2）。
 *
 * <h3>为什么需要这个测试：拼错的配置项是<b>静默失效</b>的</h3>
 * {@code spring.datasource.dynamic.druid} 绑到 dynamic-datasource 的 {@link DruidConfig}，
 * 该类<b>没有</b> {@code ignoreUnknownFields = false}。这意味着：
 *
 * <pre>
 *   max-wait: 60000     →  生效
 *   max-waite: 60000    →  编译不报错、启动不报错、日志无提示，直接被丢弃
 * </pre>
 *
 * 于是连接池会以 Druid 的默认值跑：{@code maxWait = -1}（无限等待）、
 * {@code validationQuery = null}（testWhileIdle 形同虚设）、
 * {@code removeAbandoned = false}（泄漏永不回收）。
 * 这类问题只在高峰期「请求全挂住」时才暴露，归因成本极高。
 *
 * <p>本测试直接读<b>真实 yml 文件</b>（不是把值抄一份进测试），
 * 用 Spring Boot 自己的 {@link Binder} 绑定后逐个断言，
 * 因此任何 key 拼写漂移都会在这里变红，而不是等到线上。
 *
 * <h3>参数怎么定的（以及刻意<b>没有</b>配什么）</h3>
 * <ul>
 *   <li>{@code test-on-borrow=false}：借出时探活每次多一个 RTT，
 *       高并发下纯属白给；靠 {@code test-while-idle} 在驱逐线程里兜底即可。</li>
 *   <li>{@code filters=stat}：<b>刻意不挂 slf4j</b>。slf4j 过滤器会把<b>每条</b> SQL
 *       都打进日志，与 V3.1-B A3「生产日志收敛到 info」直接冲突，
 *       磁盘与 IO 会被打爆。慢 SQL 只进 Druid 统计，不落日志。</li>
 *   <li><b>没有配 {@code query-timeout}</b>：那是给<b>每条 Statement</b> 设超时，
 *       会把数据监控查询、批量导入这类<b>合法</b>的慢语句一起掐掉。</li>
 *   <li>{@code remove-abandoned-timeout-millis} 必须 <b>大于</b> 最长事务/批处理耗时，
 *       否则会把正常长事务的连接掐断 → 数据不一致。已知最长的是迁移引擎执行与批量导入，
 *       3 分钟留足余量（本测试用 60s 下限钉死，防止有人手滑调小）。</li>
 * </ul>
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · P1-B2）
 */
class DruidPoolProductionConfigTest {

    private static final String PREFIX = "spring.datasource.dynamic.druid";

    @Test
    @DisplayName("B2-1 获取连接与有效性检测参数必须真的绑上（拼错的 key 会被静默忽略）")
    void acquireAndValidationParametersAreBound() {
        DruidConfig cfg = bind("prod");

        // Druid 默认 maxWait = -1（无限等待）→ 池满后请求全部挂住且不报错
        assertEquals(60000, cfg.getMaxWait().intValue(),
            "max-wait 必须为 60000ms：Druid 默认 -1 会让请求无限挂起");

        // validationQuery 为空时 test-while-idle 不产生任何探活，形同虚设
        assertEquals("SELECT 1", cfg.getValidationQuery(),
            "MySQL 探活语句缺失会让 test-while-idle 失效");
        assertEquals(3, cfg.getValidationQueryTimeout().intValue());

        assertTrue(cfg.getTestWhileIdle(), "必须开启空闲探活，否则会被 MySQL wait_timeout 掐断");
        assertFalse(cfg.getTestOnBorrow(), "借出时探活每次多一次 RTT，生产不划算");
        assertFalse(cfg.getTestOnReturn(), "归还时探活无收益");
    }

    @Test
    @DisplayName("B2-2 空闲驱逐与保活参数必须真的绑上，且保活间隔必须小于最短可驱逐空闲时间")
    void evictionAndKeepAliveParametersAreBound() {
        DruidConfig cfg = bind("prod");

        assertEquals(60000L, cfg.getTimeBetweenEvictionRunsMillis().longValue());
        assertEquals(300000L, cfg.getMinEvictableIdleTimeMillis().longValue());
        assertEquals(900000L, cfg.getMaxEvictableIdleTimeMillis().longValue());

        assertTrue(cfg.getKeepAlive());
        long keepAliveBetween = cfg.getKeepAliveBetweenTimeMillis();
        long minEvictable = cfg.getMinEvictableIdleTimeMillis();
        assertTrue(keepAliveBetween < minEvictable,
            "keep-alive-between-time-millis(" + keepAliveBetween + ") 必须小于 "
                + "min-evictable-idle-time-millis(" + minEvictable + ")，否则保活永远轮不到");
    }

    @Test
    @DisplayName("B2-3 连接泄漏回收必须开启，且超时阈值不得小于 60s（否则会掐断正常长事务）")
    void leakRecyclingParametersAreBound() {
        DruidConfig cfg = bind("prod");

        assertTrue(cfg.getRemoveAbandoned(), "不开泄漏回收，忘记 close 的连接会永久占位");
        assertTrue(cfg.getLogAbandoned(), "不打借出堆栈就无法定位泄漏点");

        // Druid 的 removeAbandonedTimeout 单位是**毫秒**（DruidConfig 里是 Integer）
        int timeoutMs = cfg.getRemoveAbandonedTimeoutMillis();
        assertTrue(timeoutMs >= 60_000,
            "泄漏回收阈值 " + timeoutMs + "ms 过小：必须大于最长事务/批处理耗时（迁移引擎执行、批量导入），"
                + "否则会把正常长事务掐断导致数据不一致");
    }

    @Test
    @DisplayName("B2-4 慢 SQL 走 stat 统计而非 slf4j 日志（防止生产日志被打爆）")
    void slowSqlUsesStatFilterNotLogger() {
        DruidConfig cfg = bind("prod");
        String filters = cfg.getFilters();

        assertNotNull(filters);
        assertTrue(filters.contains("stat"), "filters 必须包含 stat 才能统计慢 SQL");
        assertFalse(filters.contains("slf4j"),
            "filters 不得包含 slf4j：它会把每条 SQL 都打进日志，与 A3 日志收敛直接冲突（磁盘/IO 被打爆）");

        // ⚠️ 慢 SQL 阈值走 DruidConfig#stat 这个 Map，**不是** connection-properties。
        //    实测：把 "druid.stat.slowSqlMillis=1000" 写进 connection-properties 的字符串里
        //    绑不上（Properties 类型的字段不接受裸字符串），配置会被静默丢弃。
        //    正确形态是 stat: { slow-sql-millis: 1000 }，由 DruidStatConfigUtil 转 setter。
        Map<String, Object> stat = cfg.getStat();
        assertNotNull(stat, "stat 配置未绑定：慢 SQL 阈值不会生效");
        assertEquals("1000", String.valueOf(stat.get("slow-sql-millis")));
        assertEquals("true", String.valueOf(stat.get("merge-sql")));
        assertEquals("false", String.valueOf(stat.get("log-slow-sql")),
            "log-slow-sql 必须为 false：StatFilter 用 ERROR 级别打慢 SQL，会污染错误日志");
    }

    @Test
    @DisplayName("B2-5 容量按 profile 收敛：prod 50/10，dev/test 保持 20/5")
    void capacityIsProfileScoped() {
        assertEquals(50, bind("prod").getMaxActive().intValue());
        assertEquals(10, bind("prod").getMinIdle().intValue());

        // dev/test 刻意不动：开发机多开、测试库并发有限，池子调大只会白白占连接
        assertEquals(20, bind("dev").getMaxActive().intValue());
        assertEquals(5, bind("dev").getMinIdle().intValue());
        assertEquals(20, bind("test").getMaxActive().intValue());
        assertEquals(5, bind("test").getMinIdle().intValue());
    }

    /**
     * 用 Spring Boot 自己的 Binder 把「application.yml + application-{profile}.yml」绑成 {@link DruidConfig}。
     *
     * <p>刻意<b>不走 Spring 上下文</b>：这里要验的只是「yml 的 key 能不能绑到配置类」，
     * 起全量上下文会引入 DB/Redis/ES 依赖，让这条守卫变得又慢又脆。
     */
    private static DruidConfig bind(String profile) {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        MutablePropertySources sources = new MutablePropertySources();
        for (String name : new String[]{"application.yml", "application-" + profile + ".yml"}) {
            List<PropertySource<?>> loaded;
            try {
                loaded = loader.load(name, new ClassPathResource(name));
            } catch (Exception e) {
                throw new IllegalStateException("无法加载 " + name + " 用于守卫断言", e);
            }
            assertTrue(!loaded.isEmpty(), name + " 解析结果为空");
            sources.addLast(loaded.get(0));
        }
        // 注意是 from(Iterable<PropertySource<?>>)，不是 get(Environment)——这里没有 Environment
        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        return binder.bind(PREFIX, Bindable.of(DruidConfig.class)).orElseGet(DruidConfig::new);
    }
}
