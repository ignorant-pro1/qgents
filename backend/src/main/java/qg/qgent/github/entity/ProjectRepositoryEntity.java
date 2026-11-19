package qg.qgent.github.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 项目显式导入的仓库；与 Workspace 的生命周期无关。
 */
@Data
@TableName("project_repositories")
public class ProjectRepositoryEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private Long installationId;
    private Long providerRepositoryId;
    private String fullName;
    private String defaultBranch;
    private String htmlUrl;
    private Boolean privateRepository;
}
