package qg.qgent.team.mapper.view;

import lombok.Data;

/**
 * 团队成员查询结果，包含用户昵称、邮箱和头像。
 */
@Data
public class TeamMemberView {
    private Long teamId;
    private Long userId;
    private String role;
    // 成员用户显示名（users.display_name）。
    private String displayName;
    // 成员用户邮箱（users.email）。
    private String email;
    // 成员用户头像 URL（users.avatar_url，可为空）。
    private String avatarUrl;
}
