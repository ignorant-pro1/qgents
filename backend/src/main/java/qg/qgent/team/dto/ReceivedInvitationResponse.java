package qg.qgent.team.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前用户收到的团队邀请（收件人视角）视图。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceivedInvitationResponse {
    private String id;
    private String teamId;
    private String teamName;
    private String role;
    private String inviterDisplayName;
    private String status;
    private String expiresAt;
    private String createdAt;
}
