package qg.qgent.team.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 团队成员角色变更：仅团队所有者可设置 TEAM_ADMIN 或 TEAM_MEMBER。
 */
@Data
public class TeamMemberUpdateRequest {
    @NotBlank
    private String role;
}
