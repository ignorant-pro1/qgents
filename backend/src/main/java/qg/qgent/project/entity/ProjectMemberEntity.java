package qg.qgent.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("project_members")
public class ProjectMemberEntity {
    private Long projectId;
    private Long userId;
    private String role;
}
