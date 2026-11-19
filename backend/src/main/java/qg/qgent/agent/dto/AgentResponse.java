package qg.qgent.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import qg.qgent.agent.entity.AgentEntity;

/**
 * 项目内 Agent 视图：列表接口只返回已启用的 Agent，提交申请返回新建的待审批申请。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {

    // Agent ID。
    @Schema(description = "Agent ID")
    private String id;

    @Schema(description = "Agent 名称")
    private String name;

    @Schema(description = "Agent 职责描述")
    private String description;

    @Schema(description = "Agent 头像 URL，可为空")
    private String avatarUrl;

    @Schema(description = "是否项目默认 Agent")
    private boolean defaultAgent;

    @Schema(description = "状态枚举：PENDING（刚提交的申请）/ACTIVE（已启用，可邀请入群）；列表接口只返回 ACTIVE")
    private String status;

    public static AgentResponse of(AgentEntity agent) {
        return new AgentResponse(
            agent.getId().toString(),
            agent.getName(),
            agent.getDescription(),
            agent.getAvatarUrl(),
            Boolean.TRUE.equals(agent.getDefaultAgent()),
            agent.getStatus());
    }
}
