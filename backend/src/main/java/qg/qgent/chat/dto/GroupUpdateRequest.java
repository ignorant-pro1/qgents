package qg.qgent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改群标题；null 表示不修改，标题不能为空白。
 */
@Data
public class GroupUpdateRequest {

    // 新群标题（≤255，null 表示不修改）。
    @Size(max = 255)
    @Schema(description = "新群标题，null 表示不修改", maxLength = 255)
    private String title;
}
