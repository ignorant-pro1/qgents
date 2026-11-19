package qg.qgent.attachment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 附件元数据与短期内容访问地址；不读取文件字节。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentInfoResponse {

    @Schema(description = "附件 ID")
    private String attachmentId;

    @Schema(description = "原始文件名")
    private String fileName;

    @Schema(description = "MIME 媒体类型")
    private String mediaType;

    @Schema(description = "文件大小（字节）")
    private Long sizeBytes;

    @Schema(description = "预览类型：IMAGE/PDF/TEXT/CODE/UNSUPPORTED")
    private String previewType;

    @Schema(description = "是否可内联预览")
    private boolean previewable;

    @Schema(description = "短期内容地址（相对路径，带 token 查询参数）")
    private String contentUrl;

    @Schema(description = "contentUrl 过期时间")
    private LocalDateTime expiresAt;
}
