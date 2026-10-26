package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.infrastructure.job.SchedulerConfiguration;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.quartz.Scheduler;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库与真实经过时间验证锁续期，不以SQL改时间或内存替身代替原期限后的互斥证据。 */
class ShedLockRenewalIntegrationTest extends MariaDbIntegrationTest {
    private final ApplicationContext parent;
    private final JdbcTemplate jdbc;
    private final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();
    private final List<SimpleLock> locks = new ArrayList<>();
    private final Set<String> names = new HashSet<>();
    private final Set<String> instanceIds = new HashSet<>();
    private final Set<String> lockNames = new HashSet<>();

    /**
     * 注入已迁移的独立数据库与父上下文。
     * @param parent 完整应用基础资源
     * @param jdbc 独立时钟/租期断言及自身记录清理
     */
    @Autowired
    ShedLockRenewalIntegrationTest(ApplicationContext parent, JdbcTemplate jdbc) {
        this.parent = parent; this.jdbc = jdbc;
    }

    /** 先取消自己的续期锁并关闭提供者，再清理随机锁行与调度命名空间，核对全部线程退出。 */
    @AfterEach
    void cleanup() {
        try {
            for (var lock : locks) lock.unlock();
        } finally {
            for (var context : contexts) context.close();
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.isAlive() && (names.stream().anyMatch(name -> thread.getName().contains(name))
                        || instanceIds.stream().anyMatch(id -> thread.getName().contains(id)))));
        for (String lock : lockNames) jdbc.update("DELETE FROM stratum_shedlock WHERE name=?", lock);
        for (String name : names) {
            jdbc.update("DELETE FROM STRATUM_QRTZ_LOCKS WHERE SCHED_NAME=?", name);
            jdbc.update("DELETE FROM STRATUM_QRTZ_SCHEDULER_STATE WHERE SCHED_NAME=?", name);
        }
    }

    /** 两个真实提供者在原30秒截止之后仍互斥；主动释放后第二提供者立即可取。 */
    @Test
    void renewsBeyondOriginalDeadlineAndReleasesCorrectly() throws Exception {
        String name = "lock:job:renew:" + UUID.randomUUID();
        var first = provider();
        var second = provider();
        var owner = lock(first, name, Duration.ofSeconds(30)).orElseThrow();
        Instant original = jdbc.queryForObject("SELECT lock_until FROM stratum_shedlock WHERE name=?", LocalDateTime.class, name)
                .toInstant(ZoneOffset.UTC);
        await().pollInterval(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(35)).until(() ->
                jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class).toInstant(ZoneOffset.UTC)
                        .isAfter(original.plusSeconds(1)));
        Instant renewed = jdbc.queryForObject("SELECT lock_until FROM stratum_shedlock WHERE name=?", LocalDateTime.class, name)
                .toInstant(ZoneOffset.UTC);
        assertTrue(renewed.isAfter(original));
        assertTrue(lock(second, name, Duration.ofSeconds(30)).isEmpty());
        owner.unlock();
        assertTrue(lock(second, name, Duration.ofSeconds(30)).isPresent());
    }

    /** 本实例四个续期名额耗尽时不创建额外锁，重复解锁不超发容量，拒绝短租期后不泄漏名额。 */
    @Test
    void boundsRenewalsAndDoesNotLeakOrOverReleaseCapacity() throws Exception {
        var provider = provider();
        String prefix = "lock:job:capacity:" + UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> lock(provider, prefix, Duration.ofSeconds(29)));
        assertThrows(IllegalArgumentException.class, () -> lock(provider, "unsafe\nname", Duration.ofSeconds(30)));
        var active = new ArrayList<SimpleLock>();
        for (int index = 0; index < 4; index++) active.add(lock(provider, prefix + index, Duration.ofSeconds(30)).orElseThrow());
        assertTrue(lock(provider, prefix + "extra", Duration.ofSeconds(30)).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stratum_shedlock WHERE name=?", Integer.class, prefix + "extra"));
        active.getFirst().unlock(); active.getFirst().unlock();
        assertTrue(lock(provider, prefix + "extra", Duration.ofSeconds(30)).isPresent());
        assertTrue(lock(provider, prefix + "overflow", Duration.ofSeconds(30)).isEmpty());
    }

    /**
     * 构造独立提供者及生产续期配置，线程数固定为1，容量派生为4。
     * @return 独立上下文中的实际数据库提供者
     * @throws Exception 调度器实例标识读取失败
     */
    private LockProvider provider() throws Exception {
        String name = "renew-" + UUID.randomUUID(); names.add(name);
        var context = new AnnotationConfigApplicationContext(); contexts.add(context);
        context.setParent(parent);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("renewal-test", Map.of(
                "stratum.jobs.enabled", true, "stratum.jobs.name", name, "stratum.jobs.workers", 1)));
        context.register(SchedulerConfiguration.class); context.refresh();
        assertTrue(context.getBeansOfType(java.util.concurrent.Executor.class).isEmpty(), "内部续期池不能改变应用Executor装配");
        instanceIds.add(context.getBean(Scheduler.class).getSchedulerInstanceId());
        return context.getBean(LockProvider.class);
    }

    /**
     * 通过生产提供者尝试取得随机锁，并登记本用例的释放责任。
     * @param provider 实际提供者
     * @param name 锁名称
     * @param lease 数据库租期
     * @return 可空真实锁
     */
    private java.util.Optional<SimpleLock> lock(LockProvider provider, String name, Duration lease) {
        lockNames.add(name);
        var result = provider.lock(new LockConfiguration(Instant.now(), name, lease, Duration.ZERO));
        result.ifPresent(locks::add);
        return result;
    }
}
