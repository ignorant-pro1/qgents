package qg.qgent.team.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 团队级 SSE 事件实体。
 */
@Data
@TableName(value = "team_events", autoResultMap = true)
public class TeamEventEntity {

    // 事件 ID。
    @TableId(type = IdType.AUTO)
    private Long id;

    // 所属团队 ID。
    private Long teamId;

    // 团队内单调递增事件序号（SSE 游标）。
    private Long sequenceNo;

    // 事件类型（project.member.added / team.member.updated / activity.created）。
    private String eventType;

    // 关联资源 ID（projectId 等）；可为空。
    private String resourceId;

    // 脱敏事件载荷。
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    // 产生时间（UTC）。
    private LocalDateTime createdAt;
}
