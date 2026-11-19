package qg.qgent.team.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 团队资料和当前用户的团队角色。
 */
@Data
@NoArgsConstructor
public class TeamResponse {
    private String id;
    private String name;
    private String role;
    // 团队成员数，用于前端团队卡片展示。
    private Integer memberCount;
    // 团队简介，可为空。
    private String description;
    // 团队头像 URL（OSS 公共读长期地址，可为空）。
    private String avatarUrl;
    // 创建时间（UTC）。
    private LocalDateTime createdAt;

    public TeamResponse(String id, String name, String role) {
        this.id = id;
        this.name = name;
        this.role = role;
    }
}
