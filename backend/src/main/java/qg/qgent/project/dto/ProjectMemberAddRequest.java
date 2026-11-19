package qg.qgent.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProjectMemberAddRequest {
    @NotNull
    @Schema(type = "string")
    private Long userId;
}
