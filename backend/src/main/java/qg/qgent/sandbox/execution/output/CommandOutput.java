package qg.qgent.sandbox.execution.output;

import qg.qgent.sandbox.runtime.CommandExecutionResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一命令输出的流式保存、有界脱敏保存和读取窗口；输出位于工作区旁侧目录。
 */
public final class CommandOutput {
    private CommandOutput() {
    }

    public static Path directory(Path workspace, Long id) {
        return workspace
            .getParent()
            .resolve(".qgents-output")
            .resolve(workspace.getFileName())
            .resolve(id.toString());
    }

    /**
     * 非流式执行结束后保存结果；带凭证的命令只保存有界、脱敏后的输出。
     */
    public static void save(
        Path directory, CommandExecutionResult result, Map<String, String> environment) {
        try {
            Files.createDirectories(directory);
            for (String stream : List.of("stdout", "stderr")) {
                String text =
                    String.join(
                        "\n",
                        "stdout".equals(stream)
                            ? result.getStandardOutput()
                            : result.getStandardError());
                Files.writeString(
                    directory.resolve(stream),
                    (String) redact(Map.of("text", text), environment).get("text"));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("保存命令输出失败", exception);
        }
    }

    public static Map<String, Object> read(
        Path directory, String stream, long offset, int maxBytes) {
        Path file = directory.resolve(stream);
        if (!Files.exists(file))
            return Map.of("text", "", "nextOffset", offset, "truncated", false);
        try (var input = java.nio.channels.FileChannel.open(file)) {
            long size = input.size();
            input.position(Math.min(offset, size));
            var buffer =
                java.nio.ByteBuffer.allocate(
                    (int) Math.min(maxBytes, Math.max(0, size - offset)));
            while (buffer.hasRemaining() && input.read(buffer) != -1) {
            }
            buffer.flip();
            var text = java.nio.CharBuffer.allocate(buffer.remaining());
            var decoder =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE);
            decoder.decode(buffer, text, false);
            long next = offset + buffer.position();
            text.flip();
            return Map.of("text", text.toString(), "nextOffset", next, "truncated", next < size);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("读取命令输出失败", exception);
        }
    }

    public static Map<String, Object> tail(Path directory, String stream) {
        Path file = directory.resolve(stream);
        if (!Files.exists(file)) return Map.of("text", "", "truncated", false);
        try (var input = java.nio.channels.FileChannel.open(file)) {
            long size = input.size();
            long offset = Math.max(0, size - 16000);
            input.position(offset);
            var buffer = java.nio.ByteBuffer.allocate((int) Math.min(size, 16000));
            while (buffer.hasRemaining() && input.read(buffer) != -1) {
            }
            buffer.flip();
            if (offset > 0)
                while (buffer.hasRemaining() && (buffer.get(buffer.position()) & 0xc0) == 0x80)
                    buffer.get();
            return Map.of(
                "text",
                StandardCharsets.UTF_8.decode(buffer).toString(),
                "truncated",
                offset > 0);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("读取命令输出失败", exception);
        }
    }

    public static Map<String, Object> redact(
        Map<String, Object> value, Map<String, String> environment) {
        Map<String, Object> result = new LinkedHashMap<>(value);
        String text = (String) result.getOrDefault("text", "");
        for (String secret : environment.values())
            if (secret != null && secret.length() >= 8) text = text.replace(secret, "[redacted]");
        result.put("text", text);
        return result;
    }

    /**
     * 限制内存摘要，同时按需将完整字节流保存到输出文件。
     */
    public static final class LimitedOutputStream extends OutputStream {
        private final int limit;
        private final ByteArrayOutputStream delegate;
        private boolean truncated;
        private final OutputStream fullOutput;

        public LimitedOutputStream(int limit, Path path) {
            this.limit = limit;
            try {
                if (path != null) Files.createDirectories(path.getParent());
                fullOutput =
                    path == null
                        ? OutputStream.nullOutputStream()
                        : Files.newOutputStream(path);
            } catch (IOException exception) {
                throw new IllegalStateException("无法创建输出文件", exception);
            }
            this.delegate = new ByteArrayOutputStream(Math.min(limit, 8192));
        }

        @Override
        public void write(int value) throws IOException {
            fullOutput.write(value);
            if (delegate.size() < limit) {
                delegate.write(value);
            } else {
                truncated = true;
            }
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            fullOutput.write(values, offset, length);
            fullOutput.flush();
            int writable = Math.min(length, Math.max(0, limit - delegate.size()));
            if (writable > 0) {
                delegate.write(values, offset, writable);
            }
            truncated |= writable < length;
        }

        @Override
        public void close() throws IOException {
            fullOutput.close();
        }

        public List<String> lines() {
            String value = value();
            if (truncated) value += System.lineSeparator() + "[输出超过限制，已截断]";
            return value.isEmpty() ? List.of() : Arrays.asList(value.split("\\R"));
        }

        String value() {
            return delegate.toString(StandardCharsets.UTF_8);
        }

        boolean isTruncated() {
            return truncated;
        }
    }
}
