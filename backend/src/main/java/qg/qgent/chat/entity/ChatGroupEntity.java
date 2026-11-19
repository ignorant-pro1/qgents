package qg.qgent.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 默认群共享项目成员；新建群仅包含受邀用户和 Agent。
 */
@Data
@TableName("chat_groups")
public class ChatGroupEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private Long createdBy;

    private Boolean defaultGroup = true;
    private String name;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
