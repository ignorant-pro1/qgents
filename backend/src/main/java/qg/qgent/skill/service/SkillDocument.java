package qg.qgent.skill.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import qg.qgent.common.web.ApiException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SkillDocument(String name, String description, String content) {
    private static final int MAX_BYTES = 256 * 1024;
    private static final Pattern FRONTMATTER =
        Pattern.compile(
            "\\A\\uFEFF?---\\r?\\n(.*?)^---(?:\\r?\\n|\\z)",
            Pattern.DOTALL | Pattern.MULTILINE);

    public static SkillDocument fromFile(MultipartFile file) {
        if (!"SKILL.md".equals(file.getOriginalFilename()) || file.getSize() > MAX_BYTES) {
            throw invalid("文件名必须是 SKILL.md，大小不能超过 256 KiB");
        }
        try (InputStream input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw invalid("文件大小不能超过 256 KiB");
            }
            return parse(
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException e) {
            throw invalid("文件必须是有效的 UTF-8");
        } catch (IOException e) {
            throw new UncheckedIOException("读取 SKILL.md 失败", e);
        }
    }

    public static SkillDocument parse(String content) {
        try {
            if (StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(content)).remaining()
                > MAX_BYTES) {
                throw invalid("内容大小不能超过 256 KiB");
            }
        } catch (CharacterCodingException e) {
            throw invalid("内容必须是有效的 UTF-8");
        }
        Matcher matcher = FRONTMATTER.matcher(content);
        if (!matcher.find() || content.substring(matcher.end()).isBlank()) {
            throw invalid("必须包含 frontmatter 和非空正文");
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object parsed;
        try {
            parsed = new Yaml(new SafeConstructor(options)).load(matcher.group(1));
        } catch (YAMLException e) {
            throw invalid("frontmatter 不是有效的安全 YAML");
        }
        if (!(parsed instanceof Map<?, ?> fields)
            || !(fields.get("name") instanceof String name)
            || name.isBlank()
            || !(fields.get("description") instanceof String description)
            || description.isBlank()) {
            throw invalid("frontmatter 必须包含非空字符串 name 和 description");
        }
        if (name.length() > 255 || description.length() > 1024) {
            throw invalid("name 不能超过 255 字符，description 不能超过 1024 字符");
        }
        return new SkillDocument(name, description, content);
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SKILL_DOCUMENT", message);
    }
}
