package qg.qgent.team.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("teams")
public class TeamEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerUserId;
    private String name;
    // 团队简介，可为空；由团队所有者或管理员在创建/修改团队时设置。
    private String description;

    // 团队头像 URL，由创建或修改团队接口保存。
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String avatarUrl;

    private String status;
    // 创建时间（UTC），由数据库 DEFAULT CURRENT_TIMESTAMP(6) 生成。
    private LocalDateTime createdAt;
}
