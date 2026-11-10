package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bu.management.entity.AiTask;
import com.bu.management.mapper.AiTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * 统一 AI 任务编排：入队落一行 {@code ai_task}，由本类的调度器抢夺式执行。
 *
 * <p><b>为什么不直接在业务里 @Async</b>：@Async 抛异常就丢了、重启就丢了、多实例会重复跑，
 * 也没法「失败了隔一会儿再试」。落表 + 调度器把「执行」变成可重试、可观察、可跨实例独占的动作。
 *
 * <p><b>抢夺式执行</b>：先 {@code update ai_task set status='RUNNING', attempt=attempt+1
 * where id=? and status='PENDING'}，影响行数为 1 才调 handler。改不到说明别的执行者已经抢走，
 * 直接跳过——这是唯一保证「同一行只被执行一次」的地方，任何改动都不能绕过它。
 *
 * <p><b>重试</b>：handler 抛异常且 {@code attempt < max_attempt} 时回 PENDING，{@code next_run_at}
 * 后移 {@code 30s * 2^(attempt-1)}（上限 30 分钟）；否则置 FAILED 并把原因截 500 字记到 {@code error}。
 *
 * <p><b>开关</b>：{@code ai.platform.task-enabled} 为 false 时 {@link #dispatch()} 直接返回，
 * 但 {@link #enqueue} 照常写库——这样灰度期「先攒任务不出事」，打开开关后历史任务自然补跑。
 *
 * <p><b>处理器注册</b>：构造注入 {@code List<AiTaskHandler>}，按 {@link AiTaskHandler#type()} 建 Map；
 * type 重复或为空在启动期直接失败，避免线上把任务交给错误的处理器。
 *
 * @author BU Team
 * @since 2026-12-10
 */
@Slf4j
@Service
public class AiTaskService {

    /** 一轮最多取多少行：单轮别跑太久，剩下的下一轮（30s 后）接着跑。 */
    private static final int BATCH_LIMIT = 20;

    /** 新任务的默认最大执行次数。 */
    private static final int DEFAULT_MAX_ATTEMPT = 3;

    /** 退避基数：首次失败 30s 后重试。 */
    private static final long BASE_BACKOFF_SECONDS = 30L;

    /** 退避上限：再慢也别超过 30 分钟。 */
    private static final long MAX_BACKOFF_SECONDS = 1800L;

    /** {@code error} 列宽 500，超了会插入失败，所以落库前统一截断。 */
    private static final int ERROR_MAX_LENGTH = 500;

    /** 日志里 payload 预览长度：只为了看清是什么任务，不打印全量入参。 */
    private static final int PAYLOAD_PREVIEW_LENGTH = 200;

    /** 开关所在配置组与键；库里配了以库为准，否则用 application.yml 的默认值。 */
    private static final String CONFIG_GROUP = "ai-platform";
    private static final String CONFIG_KEY_TASK_ENABLED = "task.enabled";

    private final AiTaskMapper aiTaskMapper;

    private final ObjectMapper objectMapper;

    /** application.yml 的 {@code ai.platform.task-enabled}，库里的配置项优先。 */
    private final boolean taskEnabledByDefault;

    /** 系统配置服务：允许不改重启就灰度开关；允许为 null（单元测试/极简装配）。 */
    @Nullable
    private final SystemConfigService systemConfigService;

    /** type → handler，构造期定死，不可变。 */
    private final Map<String, AiTaskHandler> handlers;

    public AiTaskService(AiTaskMapper aiTaskMapper,
                         ObjectMapper objectMapper,
                         @Value("${ai.platform.task-enabled:true}") boolean taskEnabled,
                         @Nullable SystemConfigService systemConfigService,
                         List<AiTaskHandler> handlerList) {
        this.aiTaskMapper = aiTaskMapper;
        this.objectMapper = objectMapper;
        this.taskEnabledByDefault = taskEnabled;
        this.systemConfigService = systemConfigService;
        Map<String, AiTaskHandler> byType = new LinkedHashMap<>();
        if (handlerList != null) {
            for (AiTaskHandler handler : handlerList) {
                if (handler == null) {
                    continue;
                }
                String type = handler.type();
                if (!StringUtils.hasText(type)) {
                    throw new IllegalStateException("AI 任务处理器未声明 type：" + handler.getClass().getName());
                }
                // 重复 type 直接启动失败：宁可起不来，也别让线上任务随机落到某一个实现上
                AiTaskHandler previous = byType.putIfAbsent(type, handler);
                if (previous != null) {
                    throw new IllegalStateException("AI 任务处理器 type 重复：" + type + " → "
                            + previous.getClass().getName() + " / " + handler.getClass().getName());
                }
            }
        }
        this.handlers = Collections.unmodifiableMap(byType);
        log.info("AI 任务处理器注册完成：{} 个 type={}", this.handlers.size(), this.handlers.keySet());
    }

    // ==================== 入队 ====================

    /**
     * 入队一条任务：立刻落 PENDING 行，{@code next_run_at = now}，下一轮调度就可能执行。
     *
     * <p>不校验 type 是否已注册——任务可能先于处理器上线（灰度期先攒队列），
     * 真的没注册时执行阶段会按重试策略处理并留下 {@code error} 说明原因。
     *
     * @return 落库后的行（含自增 id），调用方可以拿 id 做关联
     */
    public AiTask enqueue(String type, Long ownerUserId, String payloadJson) {
        if (!StringUtils.hasText(type)) {
            throw new IllegalArgumentException("AI 任务类型不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        AiTask task = new AiTask();
        task.setType(type);
        task.setStatus(AiTask.STATUS_PENDING);
        task.setAttempt(0);
        task.setMaxAttempt(DEFAULT_MAX_ATTEMPT);
        task.setNextRunAt(now);
        task.setPayload(payloadJson);
        task.setOwnerUserId(ownerUserId);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        aiTaskMapper.insert(task);
        log.info("AI 任务入队: taskId={} type={} owner={} payload={}",
                task.getId(), type, ownerUserId, payloadPreview(payloadJson));
        return task;
    }

    /**
     * 事务内入队：<b>立刻插行</b>，事务提交后只补一条日志。
     *
     * <p>为什么不是「提交后再插」：afterCommit 里抛异常没人接（事务已提交，无法回滚），
     * 而且 afterCommit 拿不到返回值，调用方就没法把 taskId 写进业务行做关联。
     * 插入本身不受异步可见性影响（读的是本事务自己写的行），真正的风险在「执行」而不是「入队」——
     * 而执行由调度器在提交之后才可能扫到（30s 一轮，慢于任何本地事务），所以这里插行是安全的。
     * 若外层事务回滚，这行会一起回滚，相当于没入队，这正是我们想要的语义。
     *
     * @return 落库后的行（含自增 id）
     */
    public AiTask enqueueAfterCommit(String type, Long ownerUserId, String payloadJson) {
        AiTask task = enqueue(type, ownerUserId, payloadJson);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 没有事务就没有「提交后」，直接视为已入队
            return task;
        }
        Long taskId = task.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.debug("AI 任务已随事务提交: taskId={} type={}（执行交给调度器）", taskId, type);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    log.info("事务未提交，AI 任务行随事务回滚: taskId={} type={} status={}", taskId, type, status);
                }
            }
        });
        return task;
    }

    // ==================== 调度 ====================

    /**
     * 每 30 秒扫一轮到期任务并逐行执行。
     *
     * <p>固定延迟（fixedDelay）而不是固定频率：上一轮跑完再等 30s，避免慢任务把调度线程堆满。
     * 单行任何异常都在本行内消化，不影响同一轮里的其它行，也不影响下一轮。
     */
    @Scheduled(fixedDelay = 30_000, zone = "Asia/Shanghai")
    public void dispatch() {
        if (!isTaskEnabled()) {
            // 开关关闭时连库都不查：灰度期关掉编排要能做到零数据库开销
            log.debug("AI 任务编排开关关闭（ai.platform.task-enabled=false），本轮跳过");
            return;
        }
        List<AiTask> due;
        try {
            due = aiTaskMapper.selectList(new LambdaQueryWrapper<AiTask>()
                    .eq(AiTask::getStatus, AiTask.STATUS_PENDING)
                    .le(AiTask::getNextRunAt, LocalDateTime.now())
                    .orderByAsc(AiTask::getNextRunAt)
                    .orderByAsc(AiTask::getId)
                    .last("limit " + BATCH_LIMIT));
        } catch (Exception e) {
            // 定时任务里抛异常会被调度器吞掉，这里主动记一笔，否则队列再也不会被消费却无人知晓
            log.warn("AI 任务队列查询失败: {}", e.getMessage());
            return;
        }
        if (due == null || due.isEmpty()) {
            return;
        }
        int executed = 0;
        for (AiTask row : due) {
            if (row == null || row.getId() == null) {
                continue;
            }
            try {
                if (executeOne(row)) {
                    executed++;
                }
            } catch (Exception e) {
                // 单行炸掉不能拖垮整轮：后面的行还要扫
                log.warn("AI 任务单行执行异常，已跳过: taskId={} err={}", row.getId(), e.getMessage(), e);
            }
        }
        log.info("AI 任务调度完成: 到期={} 执行成功={}", due.size(), executed);
    }

    /**
     * 执行一行：先抢夺，再按 attempt/max_attempt 决定跑不跑、跑挂了怎么办。
     *
     * @return true = 真的调了 handler 且成功
     */
    private boolean executeOne(AiTask row) {
        LocalDateTime now = LocalDateTime.now();
        int currentAttempt = row.getAttempt() == null ? 0 : row.getAttempt();
        int maxAttempt = row.getMaxAttempt() == null ? DEFAULT_MAX_ATTEMPT : row.getMaxAttempt();

        int grabbed;
        try {
            // 原子抢夺：status 必须还是 PENDING 才能改成功，attempt 在 SQL 里自增避免读改写竞态。
            // 这里用字符串列的 UpdateWrapper 而不是 LambdaUpdateWrapper：Lambda 版会即时解析列名，
            // 依赖 MyBatis-Plus 的 TableInfo 缓存（启动时才建），纯单测环境里拿不到就抛
            // "can not find lambda cache"——用列名同样安全，且没有被测环境绑死。
            grabbed = aiTaskMapper.update(null, new UpdateWrapper<AiTask>()
                    .eq("id", row.getId())
                    .eq("status", AiTask.STATUS_PENDING)
                    .set("status", AiTask.STATUS_RUNNING)
                    .set("locked_at", now)
                    .set("updated_at", now)
                    .setSql("attempt = attempt + 1"));
        } catch (Exception e) {
            log.warn("AI 任务抢夺失败，跳过本行: taskId={} err={}", row.getId(), e.getMessage());
            return false;
        }
        if (grabbed != 1) {
            log.debug("AI 任务已被其它执行者抢走，跳过: taskId={}", row.getId());
            return false;
        }

        int attempt = currentAttempt + 1;
        if (attempt > maxAttempt) {
            // 次数已经用完却还留在队列里（人工重排/历史脏数据）：直接判失败，绝不执行
            log.warn("AI 任务重试次数已用尽，置 FAILED 不再执行: taskId={} attempt={} max={}",
                    row.getId(), currentAttempt, maxAttempt);
            markFailed(row.getId(), "重试次数已用尽（attempt=" + currentAttempt + ", max=" + maxAttempt + "）");
            return false;
        }

        AiTaskHandler handler = handlers.get(row.getType());
        if (handler == null) {
            handleFailure(row, attempt, maxAttempt, new IllegalStateException("未注册的任务类型：" + row.getType()));
            return false;
        }

        log.info("AI 任务开始执行: taskId={} type={} attempt={}/{} payload={}",
                row.getId(), row.getType(), attempt, maxAttempt, payloadPreview(row.getPayload()));
        try {
            String result = handler.handle(row.getPayload());
            markSuccess(row.getId(), result);
            log.info("AI 任务执行成功: taskId={} type={} attempt={}/{}", row.getId(), row.getType(), attempt, maxAttempt);
            return true;
        } catch (Exception e) {
            handleFailure(row, attempt, maxAttempt, e);
            return false;
        }
    }

    /**
     * 失败处理：还有次数就回 PENDING 并指数退避，没次数就置 FAILED。
     */
    private void handleFailure(AiTask row, int attempt, int maxAttempt, Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (attempt < maxAttempt) {
            long backoff = backoffSeconds(attempt);
            AiTask patch = new AiTask();
            patch.setId(row.getId());
            patch.setStatus(AiTask.STATUS_PENDING);
            patch.setNextRunAt(LocalDateTime.now().plusSeconds(backoff));
            patch.setError(truncate(message));
            patch.setUpdatedAt(LocalDateTime.now());
            aiTaskMapper.updateById(patch);
            log.warn("AI 任务失败，{} 秒后重试: taskId={} type={} attempt={}/{} err={}",
                    backoff, row.getId(), row.getType(), attempt, maxAttempt, message);
            return;
        }
        markFailed(row.getId(), message);
        log.error("AI 任务失败且已达最大次数: taskId={} type={} attempt={}/{} err={}",
                row.getId(), row.getType(), attempt, maxAttempt, message, error);
    }

    /** 置 SUCCESS 并写结果；顺手清掉上一轮重试留下的 error，避免读侧看到「成功但带错」。 */
    private void markSuccess(Long taskId, String result) {
        AiTask patch = new AiTask();
        patch.setId(taskId);
        patch.setStatus(AiTask.STATUS_SUCCESS);
        patch.setResult(result);
        patch.setError("");
        patch.setUpdatedAt(LocalDateTime.now());
        aiTaskMapper.updateById(patch);
    }

    /** 置 FAILED 并记原因（截 500 字）；不再被调度器扫到。 */
    private void markFailed(Long taskId, String message) {
        AiTask patch = new AiTask();
        patch.setId(taskId);
        patch.setStatus(AiTask.STATUS_FAILED);
        patch.setError(truncate(message));
        patch.setUpdatedAt(LocalDateTime.now());
        aiTaskMapper.updateById(patch);
    }

    // ==================== 工具 ====================

    /**
     * 退避秒数：{@code 30s * 2^(attempt-1)}，30s / 60s / 120s …，上限 30 分钟。
     */
    private long backoffSeconds(int attempt) {
        int exponent = Math.max(attempt - 1, 0);
        if (exponent >= 6) {
            // 30s * 2^6 = 1920s 已超上限，再算也是白算（也顺手规避 long 移位溢出）
            return MAX_BACKOFF_SECONDS;
        }
        return Math.min(BASE_BACKOFF_SECONDS * (1L << exponent), MAX_BACKOFF_SECONDS);
    }

    /** 截断到 {@code error} 列宽，超长直接截断（不要因为错误信息太长把状态更新也弄失败）。 */
    private String truncate(String message) {
        String value = message == null ? "" : message;
        return value.length() <= ERROR_MAX_LENGTH ? value : value.substring(0, ERROR_MAX_LENGTH);
    }

    /** 开关读取：库里有配置项就以库为准（可热改），读不到/读失败按 application.yml 的默认值。 */
    private boolean isTaskEnabled() {
        if (systemConfigService == null) {
            return taskEnabledByDefault;
        }
        try {
            return systemConfigService.getBoolean(CONFIG_GROUP, CONFIG_KEY_TASK_ENABLED, taskEnabledByDefault);
        } catch (Exception e) {
            log.warn("读取 AI 任务开关失败，按默认值 {} 处理: {}", taskEnabledByDefault, e.getMessage());
            return taskEnabledByDefault;
        }
    }

    /** payload 单行预览：日志里只写压缩后的前 200 字，避免把整包上下文刷进日志。 */
    private String payloadPreview(String payloadJson) {
        if (!StringUtils.hasText(payloadJson)) {
            return "";
        }
        String compact;
        try {
            compact = objectMapper.readTree(payloadJson).toString();
        } catch (Exception e) {
            compact = payloadJson;
        }
        return compact.length() <= PAYLOAD_PREVIEW_LENGTH
                ? compact
                : compact.substring(0, PAYLOAD_PREVIEW_LENGTH) + "...";
    }
}
