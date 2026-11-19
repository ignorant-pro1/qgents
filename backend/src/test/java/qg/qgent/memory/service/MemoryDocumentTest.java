package qg.qgent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.common.web.ApiException;

/** 片段替换：原文必须唯一出现，替换后仍受写入预算约束，失败时原文件不动。 */
class MemoryDocumentTest {

    private static final int MAX_CHARS = 40;

    @TempDir Path root;

    private final MemoryDocument document = new MemoryDocument(MAX_CHARS);

    private Path directory() {
        return root.resolve("project");
    }

    @Test
    void replacesTheOnlyOccurrenceAndKeepsTheRest() {
        document.write(directory(), "# 约定\n- 邮箱登录\n- 密码 8 位");

        String updated = document.edit(directory(), "- 密码 8 位", "- 密码 12 位");

        assertThat(updated).isEqualTo("# 约定\n- 邮箱登录\n- 密码 12 位");
        assertThat(document.read(directory())).isEqualTo(updated);
    }

    @Test
    void unknownAnchorLeavesTheFileUntouched() {
        document.write(directory(), "# 约定\n- 邮箱登录");

        assertThatThrownBy(() -> document.edit(directory(), "- 手机登录", "- 手机登录"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "MEMORY_EDIT_ANCHOR_NOT_FOUND");
        assertThat(document.read(directory())).isEqualTo("# 约定\n- 邮箱登录");
    }

    @Test
    void repeatedAnchorIsRefusedInsteadOfGuessingWhichOne() {
        document.write(directory(), "# 约定\n- 邮箱登录\n- 邮箱登录");

        assertThatThrownBy(() -> document.edit(directory(), "- 邮箱登录", "- 手机登录"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "MEMORY_EDIT_ANCHOR_AMBIGUOUS");
        assertThat(document.read(directory())).isEqualTo("# 约定\n- 邮箱登录\n- 邮箱登录");
    }

    @Test
    void editThatExceedsTheBudgetLeavesTheFileUntouched() {
        document.write(directory(), "- 短");

        assertThatThrownBy(() -> document.edit(directory(), "- 短", "- " + "长".repeat(MAX_CHARS)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "MEMORY_CONTEXT_LIMIT_EXCEEDED");
        assertThat(document.read(directory())).isEqualTo("- 短");
    }
}
