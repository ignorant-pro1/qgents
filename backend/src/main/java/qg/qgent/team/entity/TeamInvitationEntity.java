package qg.qgent.team.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("team_invitations")
public class TeamInvitationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teamId;
    private Long invitedBy;
    private String emailNormalized;
    private byte[] tokenHash;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime createdAt;
}
