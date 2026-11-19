package qg.qgent.project.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 项目成员角色变更：PROJECT_ADMIN 或 PROJECT_MEMBER。
 */
@Data
public class ProjectMemberUpdateRequest {
    @NotBlank
    private String role;
}
