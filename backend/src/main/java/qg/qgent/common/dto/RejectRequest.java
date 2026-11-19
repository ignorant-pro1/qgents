package qg.qgent.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 审核拒绝请求，必须填写原因。
 */
@Data
public class RejectRequest {

    // 驳回原因（必填）。
    @NotBlank
    @Size(max = 2048)
    @Schema(description = "驳回原因（必填）", maxLength = 2048, requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;
}
