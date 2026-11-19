package qg.qgent.chat.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 消息提及对象，记录被提及的用户或 Agent。
 */
@Data
public class Mention {

    // 提及对象类型枚举：USER / AGENT。
    @NotBlank
    @Size(max = 16)
    @Schema(
        description = "提及对象类型：USER / AGENT",
        maxLength = 16,
        requiredMode = Schema.RequiredMode.REQUIRED)
    private String type;

    // 被提及的用户或 Agent ID。HTTP 与持久化 JSON 分别使用 Jackson 3/2，两套注解都要声明。
    @NotNull
    @tools.jackson.databind.annotation.JsonSerialize(
        using = tools.jackson.databind.ser.std.ToStringSerializer.class)
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(
        description = "被提及的用户或 Agent ID（十进制字符串）",
        type = "string",
        requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
}
