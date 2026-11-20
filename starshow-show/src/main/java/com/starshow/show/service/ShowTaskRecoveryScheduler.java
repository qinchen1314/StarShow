package com.starshow.show.service;

import com.starshow.show.config.ShowProperties;
import com.starshow.show.domain.entity.ShowTaskDO;
import com.starshow.show.mapper.ShowTaskMapper;
import com.starshow.show.util.ShowTimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 演出域异步任务恢复调度器（t_show_task）：
 * 按索引 (status, next_retry_at, task_id) 扫描租约过期（崩溃中断）或待重试任务，
 * 自动换新 executionId 续跑 PREHEAT 与 RESCHEDULE_SYNC 联动任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShowTaskRecoveryScheduler {

    private final ShowTaskMapper showTaskMapper;
    private final ShowPreheatService showPreheatService;
    private final ShowScheduleService showScheduleService;
    private final ShowProperties properties;

    @Scheduled(fixedDelayString = "${starshow.show.task-recovery-interval-ms:10000}")
    public void scheduledRecover() {
        if (!properties.isTaskRecoveryEnabled()) {
            return;
        }
        try {
            int recovered = recoverPendingTasks();
            if (recovered > 0) {
                log.info("ShowTaskRecoveryScheduler processed {} tasks", recovered);
            }
        } catch (Exception ex) {
            log.warn("ShowTaskRecoveryScheduler failed: {}", ex.getMessage());
        }
    }

    public int recoverPendingTasks() {
        int batchSize = Math.max(1, properties.getTaskRecoveryBatchSize());
        List<ShowTaskDO> candidates = showTaskMapper.selectRecoverableTasks(ShowTimeUtil.nowLocalDateTime(), batchSize);
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int processed = 0;
        for (ShowTaskDO task : candidates) {
            try {
                if (ShowTaskDO.TYPE_PREHEAT.equals(task.getTaskType())) {
                    showPreheatService.recoverTask(task);
                    processed++;
                } else if (ShowTaskDO.TYPE_RESCHEDULE_SYNC.equals(task.getTaskType())) {
                    if (showScheduleService.retryRescheduleSync(task)) {
                        processed++;
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to recover taskId={}, type={}, showId={}: {}",
                        task.getTaskId(), task.getTaskType(), task.getShowId(), ex.getMessage());
            }
        }
        return processed;
    }
}
