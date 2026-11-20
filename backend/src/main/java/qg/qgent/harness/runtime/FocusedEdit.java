package qg.qgent.harness.runtime;

import java.util.regex.Pattern;

/** 只识别上下文自足的单文件文本替换。复杂请求由标准对话处理。 */
final class FocusedEdit {
    private static final String PATH =
        "(?:[a-zA-Z0-9_-][a-zA-Z0-9_.-]*/)*[a-zA-Z0-9_-][a-zA-Z0-9_.-]*\\.[a-zA-Z0-9]{1,12}";
    private static final Pattern QUOTED_REPLACEMENT = Pattern.compile(
        "(?:请)?(?:在(?:已准备好的?)?默认工作区[，,]\\s*)?"
            + "(?:(?:只)?(?:修改|更新)\\s*`" + PATH + "`[，,:： ]*(?:把|将)"
            + "|(?:把|将)\\s*`" + PATH + "`\\s*(?:中|里)(?:的)?)"
            + "\\s*`[^\\r\\n`]{1,160}`\\s*(?:改成|替换为)\\s*`[^\\r\\n`]{1,160}`[。.]?");
    private static final Pattern PREPARED_WORKSPACE = Pattern.compile(
        "(?i)In the (?:already )?prepared default workspace, (?:edit|update|modify) only `?("
            + PATH + ")`?\\. Change (?:the (?:printed )?text )?from [^\\r\\n.!?]{1,160}"
            + " to [^\\r\\n.!?]{1,160}\\."
            + "(?: Then run (?:python3?|node|ruby) \\1 in the (?:actual )?sandbox"
            + "(?: using run_command)? and verify its output\\.)?"
            + "(?: Leave the change uncommitted(?: and report what you verified)?\\.)?");

    private FocusedEdit() {}

    static boolean matches(String instruction) {
        if (instruction == null || instruction.length() > 500) return false;
        String text = instruction.strip();
        return QUOTED_REPLACEMENT.matcher(text).matches()
            || PREPARED_WORKSPACE.matcher(text).matches();
    }
}
