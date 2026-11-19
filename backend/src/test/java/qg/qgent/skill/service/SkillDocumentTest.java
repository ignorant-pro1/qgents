package qg.qgent.skill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import qg.qgent.common.web.ApiException;

class SkillDocumentTest {
    private static final String VALID = "---\nname: example\ndescription: description\n---\nbody";
    private static final int LIMIT = 256 * 1024;

    @Test
    void parsesMultilineYamlAndPreservesBomAndCrlf() {
        String content =
                "﻿---\r\nname: 示例\r\ndescription: |\r\n  first\r\n  second\r\n---\r\n# 正文\r\n";
        SkillDocument document =
                SkillDocument.fromFile(file("SKILL.md", content.getBytes(StandardCharsets.UTF_8)));
        assertThat(document.name()).isEqualTo("示例");
        assertThat(document.description()).isEqualTo("first\nsecond\n");
        assertThat(document.content()).isEqualTo(content);
        assertThat(SkillDocument.parse(content)).isEqualTo(document);
    }

    @Test
    void rejectsInvalidDocuments() {
        Stream.of(
                        "body",
                        "---\nname: x\ndescription: y\n---\n  \n",
                        "---\nname: x\n---\nbody",
                        "---\nname: '  '\ndescription: y\n---\nbody",
                        "---\nname: x\ndescription: 42\n---\nbody",
                        "---\nname: x\nname: z\ndescription: y\n---\nbody",
                        "---\nname: x\ndescription: [broken\n---\nbody",
                        "---\nname: " + "n".repeat(256) + "\ndescription: y\n---\nbody",
                        "---\nname: x\ndescription: " + "d".repeat(1025) + "\n---\nbody",
                        "---\nname: x\ndescription: y\nextra: !!java.net.URL ['https://example.com']\n---\nbody",
                        "---\nname: x\ndescription: y\n---\n\uD800")
                .forEach(content -> assertInvalid(() -> SkillDocument.parse(content)));
    }

    @Test
    void validatesFilenameAndStrictUtf8() {
        assertInvalid(() ->
                SkillDocument.fromFile(file("skill.md", VALID.getBytes(StandardCharsets.UTF_8))));
        assertInvalid(() ->
                SkillDocument.fromFile(
                        file("path/SKILL.md", VALID.getBytes(StandardCharsets.UTF_8))));
        assertInvalid(
                () -> SkillDocument.fromFile(file("SKILL.md", new byte[] {(byte) 0xc3, 0x28})));
    }

    @Test
    void appliesByteLimitToBothEntrypoints() {
        String exact = VALID + "a".repeat(LIMIT - VALID.length());
        assertThat(SkillDocument.parse(exact).content()).isEqualTo(exact);
        assertThat(
                        SkillDocument.fromFile(
                                        file("SKILL.md", exact.getBytes(StandardCharsets.UTF_8)))
                                .content())
                .isEqualTo(exact);
        assertInvalid(() -> SkillDocument.parse(exact + "a"));
        assertInvalid(() ->
                SkillDocument.fromFile(
                        file("SKILL.md", (exact + "a").getBytes(StandardCharsets.UTF_8))));
        assertInvalid(() -> SkillDocument.parse(VALID + "中".repeat(LIMIT / 3)));
    }

    private static MockMultipartFile file(String filename, byte[] bytes) {
        return new MockMultipartFile("file", filename, "text/markdown", bytes);
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception ->
                                assertThat(exception.status())
                                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }
}
