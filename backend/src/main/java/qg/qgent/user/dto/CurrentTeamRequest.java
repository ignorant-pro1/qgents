package qg.qgent.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record CurrentTeamRequest(@NotNull @Schema(type = "string") Long teamId) {
}
