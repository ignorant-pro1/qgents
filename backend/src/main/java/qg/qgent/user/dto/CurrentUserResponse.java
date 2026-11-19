package qg.qgent.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import qg.qgent.project.dto.ProjectResponse;
import qg.qgent.team.dto.TeamResponse;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CurrentUserResponse {
    private UserResponse user;
    private List<TeamResponse> teams;
    private List<ProjectResponse> projects;
    private String currentTeamId;
}
