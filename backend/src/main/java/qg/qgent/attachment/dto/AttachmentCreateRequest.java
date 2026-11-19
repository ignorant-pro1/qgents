package qg.qgent.attachment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AttachmentCreateRequest {

    @NotBlank
    @Size(max = 512)
    @Schema(description = "原始文件名（必填）", maxLength = 512, requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileName;

    @Size(max = 255)
    @Schema(description = "MIME 媒体类型", maxLength = 255)
    private String contentType;

    @NotNull
    @Schema(description = "文件大小（字节，必填）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long sizeBytes;
}
