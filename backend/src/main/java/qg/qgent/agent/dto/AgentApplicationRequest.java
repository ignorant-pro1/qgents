package qg.qgent.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 项目 Agent 申请：名称与系统提示词必填，描述和头像可选。
 */
@Data
public class AgentApplicationRequest {

    // Agent 名称，在群成员与消息发送者中展示。
    @Schema(description = "Agent 名称，最多 120 字符")
    private String name;

    // Agent 职责描述。
    @Schema(description = "Agent 职责描述")
    private String description;

    // Agent 自有系统提示词，运行时追加在内置提示词之后。
    @Schema(description = "Agent 系统提示词")
    private String systemPrompt;

    // 可选头像 URL。
    @Schema(description = "Agent 头像 URL，可为空")
    private String avatarUrl;
}
