package qg.qgent.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目内 Agent；每个项目创建时得到自己的默认 Agent 记录，ID 自增且互不复用。
 * <p>
 * 自定义 Agent 先以 PENDING 申请存在，管理员在群聊审批后才变为 ACTIVE 并可邀请入群。
 */
@Data
@TableName("agents")
public class AgentEntity {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REJECTED = "REJECTED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private String name;
    private String description;
    private String systemPrompt;
    private String avatarUrl;
    private Boolean defaultAgent;
    private String status;
    private Long proposedBy;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private LocalDateTime createdAt;
}
