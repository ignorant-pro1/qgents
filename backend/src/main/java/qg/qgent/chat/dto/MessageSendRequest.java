package qg.qgent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 发送消息请求。
 * <p>
 * content 为按类型校验的结构化对象，如 {@code {"text":"..."}}；client_message_id 在同一群内唯一，
 * 断线重试命中时返回原消息。
 */
@Data
public class MessageSendRequest {

    // 消息类型：TEXT/CODE/IMAGE/FILE。
    @NotBlank
    @Size(max = 32)
    @Schema(
        description = "消息类型：TEXT/CODE/IMAGE/FILE",
        maxLength = 32,
        requiredMode = Schema.RequiredMode.REQUIRED)
    private String type;

    // 按类型校验的结构化内容对象，不能为空。
    @NotNull
    @Schema(description = "结构化内容，如 {\"text\":\"...\"}；CODE 需 language+code，IMAGE/FILE 需 url")
    private Map<String, Object> content;

    // 提及对象列表（可空）：对象数组 Mention[]，每项 {@code { type: "USER"|"AGENT", id: 十进制字符串 }}。
    // 提及本身不携带任何任务或执行环境配置。
    @Schema(description = "提及对象列表 Mention[]，每项 { type: USER|AGENT, id: 十进制字符串 }；不提及传 null 或 []")
    private List<Mention> mentions;

    // 回复或引用的原消息 ID（可空），必须属于同一群。
    @Schema(description = "回复或引用的原消息 ID，必须属于同一群", type = "string")
    private Long replyToId;

    // 客户端幂等 ID（≤128，可空），同一群内唯一。
    @Size(max = 128)
    @Schema(description = "客户端幂等 ID，同一群内唯一，断线重试返回原消息", maxLength = 128)
    private String clientMessageId;
}
