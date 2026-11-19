package qg.qgent.chat.mapper.view;

import lombok.Data;

/**
 * 群内 Agent 成员的展示字段（成员 ID 为真实 Agent ID）。
 */
@Data
public class GroupAgentRow {
    private Long agentId;
    private String name;
    private String avatarUrl;
}
