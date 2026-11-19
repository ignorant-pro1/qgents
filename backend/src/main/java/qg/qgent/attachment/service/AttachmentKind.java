package qg.qgent.attachment.service;

import java.util.Locale;
import java.util.Set;

/**
 * 附件类型及其能力：能否预览、能否按文本读取、能否作为图片读取。
 * <p>
 * 媒体类型与扩展名到类型的判定也只在这里，新增类型时不需要在其它模块补判断。
 */
public enum AttachmentKind {
    IMAGE,
    PDF,
    TEXT,
    CODE,
    UNSUPPORTED;

    private static final Set<String> CODE_MEDIA_TYPES =
        Set.of(
            "application/json",
            "application/xml",
            "application/javascript",
            "application/x-javascript",
            "application/x-yaml",
            "application/x-sh",
            "application/x-python");

    private static final Set<String> CODE_EXTENSIONS =
        Set.of(
            "java",
            "kt",
            "ts",
            "tsx",
            "js",
            "jsx",
            "py",
            "go",
            "rs",
            "c",
            "cpp",
            "cc",
            "h",
            "hpp",
            "sql",
            "sh",
            "bash",
            "zsh",
            "json",
            "yaml",
            "yml",
            "xml",
            "md",
            "markdown",
            "properties",
            "css",
            "scss",
            "html",
            "htm",
            "vue",
            "gradle",
            "toml",
            "ini",
            "cfg",
            "conf");

    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "log");

    public static AttachmentKind from(String mediaType, String fileName) {
        if (mediaType != null) {
            String lower = mediaType.toLowerCase(Locale.ROOT).trim();
            if (lower.startsWith("image/")) {
                return IMAGE;
            }
            if ("application/pdf".equals(lower)) {
                return PDF;
            }
            if (lower.startsWith("text/")) {
                return TEXT;
            }
            if (CODE_MEDIA_TYPES.contains(lower)) {
                return CODE;
            }
        }
        String extension = extension(fileName);
        if (extension == null) {
            return UNSUPPORTED;
        }
        if (CODE_EXTENSIONS.contains(extension)) {
            return CODE;
        }
        return TEXT_EXTENSIONS.contains(extension) ? TEXT : UNSUPPORTED;
    }

    public boolean previewable() {
        return this != UNSUPPORTED;
    }

    public boolean readableAsText() {
        return this == TEXT || this == CODE;
    }

    public boolean readableAsImage() {
        return this == IMAGE;
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT).trim();
    }
}
