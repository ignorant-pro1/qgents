package qg.qgent.github.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次性安装回调凭据，绑定发起人、团队和 Web/Mobile 返回入口。
 */
@Data
@TableName("github_installation_states")
public class GitHubInstallationStateEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teamId;
    private Long actorId;
    private byte[] stateHash;
    private String client;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
}
