package qg.qgent.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResetPasswordRequest {
    @NotBlank
    @Email
    @Size(max = 320)
    private String email;

    // 邮箱收到的 6 位数字验证码。
    @NotBlank
    @Size(min = 6, max = 6)
    private String token;

    @NotBlank
    @Size(max = 128)
    private String passwordKeyId;

    @NotBlank
    @Size(max = 4096)
    private String newPassword;
}
