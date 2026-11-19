package qg.qgent.attachment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentStatusResponse {

    @Schema(description = "附件 ID")
    private String attachmentId;

    @Schema(description = "附件状态")
    private String status;
}
