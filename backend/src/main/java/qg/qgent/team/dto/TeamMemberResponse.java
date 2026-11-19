package qg.qgent.team.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 团队成员及其用户资料。
 */
@Data
@NoArgsConstructor
public class TeamMemberResponse {
    private String userId;
    private String role;
    // 成员用户显示名（users.display_name）。
    private String displayName;
    // 成员用户邮箱（users.email）。
    private String email;
    // 成员用户头像 URL（users.avatar_url，可为空；OSS 未配置时也为空）。
    private String avatarUrl;

    public TeamMemberResponse(String userId, String role) {
        this.userId = userId;
        this.role = role;
    }
}
