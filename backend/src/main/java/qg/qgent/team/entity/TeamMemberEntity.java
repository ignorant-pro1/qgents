package qg.qgent.team.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("team_members")
public class TeamMemberEntity {
    private Long teamId;
    private Long userId;
    private String role;
}
