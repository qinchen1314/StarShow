package com.starshow.show.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starshow.show.domain.entity.ShowTaskDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface ShowTaskMapper extends BaseMapper<ShowTaskDO> {

    @Select("SELECT * FROM t_show_task WHERE show_id = #{showId} AND task_type = #{taskType} " +
            "AND version = #{version} AND deleted = 0 LIMIT 1")
    ShowTaskDO selectByShowTypeVersion(
            @Param("showId") long showId,
            @Param("taskType") String taskType,
            @Param("version") int version
    );

    /**
     * 原子抢占任务执行租约（支持 PENDING/FAILED 或已过期租约的 RUNNING 崩溃任务由新 executionId 接管）
     */
    @Update("UPDATE t_show_task SET status = 'RUNNING', execution_id = #{newExecutionId}, " +
            "attempt = attempt + 1, lease_until = #{leaseUntil}, failed_stage = NULL, " +
            "last_error_code = NULL, last_error_msg = NULL " +
            "WHERE task_id = #{taskId} AND deleted = 0 " +
            "AND (status IN ('PENDING', 'FAILED') OR (status = 'RUNNING' AND (lease_until IS NULL OR lease_until <= #{now})))")
    int casAcquireLease(
            @Param("taskId") long taskId,
            @Param("newExecutionId") String newExecutionId,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now
    );

    /**
     * 仅持有效 executionId 才允许推进任务至 SUCCEEDED
     */
    @Update("UPDATE t_show_task SET status = 'SUCCEEDED', failed_stage = NULL, retryable = 0, " +
            "last_error_code = NULL, last_error_msg = NULL " +
            "WHERE task_id = #{taskId} AND execution_id = #{executionId} AND status = 'RUNNING' AND deleted = 0")
    int casMarkSucceeded(@Param("taskId") long taskId, @Param("executionId") String executionId);

    /**
     * 仅持有效 executionId 才允许标记任务失败并安排重试时间
     */
    @Update("UPDATE t_show_task SET status = 'FAILED', failed_stage = #{failedStage}, retryable = #{retryable}, " +
            "next_retry_at = #{nextRetryAt}, last_error_code = #{errorCode}, last_error_msg = #{errorMsg} " +
            "WHERE task_id = #{taskId} AND execution_id = #{executionId} AND status = 'RUNNING' AND deleted = 0")
    int casMarkFailed(
            @Param("taskId") long taskId,
            @Param("executionId") String executionId,
            @Param("failedStage") String failedStage,
            @Param("retryable") int retryable,
            @Param("nextRetryAt") LocalDateTime nextRetryAt,
            @Param("errorCode") String errorCode,
            @Param("errorMsg") String errorMsg
    );

    /**
     * 改期升版本后取消旧版本未完成的任务
     */
    @Update("UPDATE t_show_task SET status = 'CANCELLED', retryable = 0, failed_stage = 'RESCHEDULED' " +
            "WHERE show_id = #{showId} AND task_type = #{taskType} AND version < #{newVersion} " +
            "AND status IN ('PENDING', 'RUNNING', 'FAILED') AND deleted = 0")
    int cancelOutdatedTasks(
            @Param("showId") long showId,
            @Param("taskType") String taskType,
            @Param("newVersion") int newVersion
    );

    /**
     * 扫描可恢复的到期任务（PENDING / FAILED 且 next_retry_at <= now，或租约过期的 RUNNING 崩溃任务）
     */
    @Select("SELECT * FROM t_show_task WHERE deleted = 0 " +
            "AND ((status IN ('PENDING', 'FAILED') AND retryable = 1 AND next_retry_at <= #{now}) " +
            "  OR (status = 'RUNNING' AND lease_until IS NOT NULL AND lease_until <= #{now})) " +
            "ORDER BY next_retry_at ASC, task_id ASC LIMIT #{limit}")
    List<ShowTaskDO> selectRecoverableTasks(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
