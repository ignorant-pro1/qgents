package qg.qgent.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName(value = "projects", autoResultMap = true)
public class ProjectEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teamId;
    private Long createdBy;
    private String name;
    private String description;

    // 项目头像 URL（OSS 公共读长期地址，可为空；确认上传后通过修改项目接口保存）。
    // 清空头像时也写入 null；更新前读取完整项目资料。
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String avatarUrl;

    private String status;
}
