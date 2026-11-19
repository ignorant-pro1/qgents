package qg.qgent.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 头像上传完成后提交的对象键。
 */
@Data
public class AvatarConfirmRequest {

    // 头像对象键（avatars/{userId}/{uuid}.{ext}），服务端签发，客户端仅原样回传。
    @NotBlank
    @Size(max = 512)
    @Schema(
        description = "头像对象键（直传凭证签发），客户端上传后原样回传",
        maxLength = 512,
        requiredMode = Schema.RequiredMode.REQUIRED)
    private String objectKey;
}
