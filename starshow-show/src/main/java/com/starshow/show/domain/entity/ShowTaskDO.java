package com.starshow.show.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 演出预热与状态推进任务恢复实体（对应表 t_show_task）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_show_task")
public class ShowTaskDO {

    public static final String TYPE_PREHEAT = "PREHEAT";
    public static final String TYPE_RESCHEDULE_SYNC = "RESCHEDULE_SYNC";
    public static final String TYPE_OPEN_SALE = "OPEN_SALE";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId
    private Long taskId;
    private Long showId;
    private String taskType;
    private Integer version;
    private String executionId;
    private String status;
    private String failedStage;
    private Integer retryable;
    private Integer attempt;
    private LocalDateTime nextRetryAt;
    private LocalDateTime leaseUntil;
    private String lastErrorCode;
    private String lastErrorMsg;

    @TableLogic
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
