package qg.qgent.project.mapper.view;

import lombok.Data;

@Data
public class ProjectMembershipView {
    private Long id;
    private Long teamId;
    private String name;
    private String description;
    private String role;
    private String status;
    private Long memberCount;
    // 项目头像 URL（可为空）。
    private String avatarUrl;
}
