package qg.qgent.team.mapper.view;

import lombok.Data;

/**
 * 批量统计团队成员数的一行。
 */
@Data
public class TeamMemberCountRow {
    private Long teamId;
    private Integer memberCount;
}
