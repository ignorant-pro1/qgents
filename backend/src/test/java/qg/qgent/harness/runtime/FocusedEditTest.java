package qg.qgent.harness.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FocusedEditTest {
    @Test
    void onlySelfContainedSingleFileEditsUseTheFocusedWorkflow() {
        assertThat(FocusedEdit.matches("把 `src/main.py` 里的 `old` 改成 `new`")).isTrue();
        assertThat(FocusedEdit.matches(
            "In the already prepared default workspace, edit only src/main.py. Change the printed text from old to new. "
                + "Then run python3 src/main.py in the actual sandbox using run_command and verify its output.")).isTrue();
        assertThat(FocusedEdit.matches("把 `../outside.py` 里的 `old` 改成 `new`")).isFalse();
        assertThat(FocusedEdit.matches("把 `src/main.py` 里的 `old` 改成 `new`，再推送 GitHub")).isFalse();
        assertThat(FocusedEdit.matches("Edit the related files using the requirements above.")).isFalse();
    }
}
