package qg.qgent.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 群记忆后台提炼的调度与进度状态，对应表 group_memory_state（一 group 一行）。
 */
@Data
@TableName("group_memory_state")
public class GroupMemoryStateEntity {

    @TableId(type = IdType.INPUT)
    private Long groupId;

    private Long projectId;

    // 已提炼到的群内消息序号；只有写入成功后才推进。
    private Long lastProcessedSequence;

    // 最近一条消息时间（UTC），用于闲置时长判定。
    private LocalDateTime lastMessageAt;

    // IDLE/RUNNING：扫描领取后置 RUNNING，用来互斥并发提炼。
    private String status;

    // 领取标识：只有持有它的任务能推进进度或写入结果，过期被接管后旧任务作废。
    private LocalDateTime claimToken;

    // 连续失败次数；新消息清零，达到上限后等待新消息再试。
    private Integer attemptCount;

    private LocalDateTime nextAttemptAt;

    // 用户通过对话提出的待应用纠错内容，多条按行累积。
    private String correction;

    private String lastError;

    // 最近一次项目记忆发现所依据的群记忆内容版本；相同版本不重复发现。
    private String discoveredVersion;

    // 项目记忆发现连续失败次数；成功后清零。
    private Integer discoveryAttemptCount;

    private LocalDateTime discoveryNextAttemptAt;

    private String discoveryError;

    private LocalDateTime updatedAt;
}
