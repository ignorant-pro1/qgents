package qg.qgent.attachment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UploadCredential {

    @Schema(description = "上传地址")
    private String uploadUrl;

    @Schema(description = "上传方法", example = "PUT")
    private String method;

    @Schema(description = "上传请求头")
    private Map<String, String> headers;

    @Schema(description = "凭证过期时间（UTC）")
    private LocalDateTime expiresAt;
}
