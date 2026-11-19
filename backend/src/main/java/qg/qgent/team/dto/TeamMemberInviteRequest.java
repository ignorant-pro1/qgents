package qg.qgent.team.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class TeamMemberInviteRequest {
    @NotBlank
    @Email
    @Size(max = 320)
    private String email;

    @NotBlank
    private String role;

    @NotNull
    @Positive
    @Max(30)
    private Integer expiresInDays;
}
