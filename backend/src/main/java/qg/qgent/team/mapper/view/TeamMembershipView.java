package qg.qgent.team.mapper.view;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TeamMembershipView {
    private Long id;
    private Long ownerUserId;
    private String name;
    private String role;
    // 团队成员数（列表查询聚合）。
    private Integer memberCount;
    private String description;
    private LocalDateTime createdAt;
    // 团队头像 URL（可为空）。
    private String avatarUrl;
}
