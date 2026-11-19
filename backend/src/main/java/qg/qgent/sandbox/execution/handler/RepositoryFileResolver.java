package qg.qgent.sandbox.execution.handler;

import org.springframework.stereotype.Component;
import qg.qgent.sandbox.exception.SandboxException;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/**
 * 将 Agent 提交的仓库相对路径解析为受控本地文件。
 */
@Component
public class RepositoryFileResolver {
    public Path resolveExisting(Path repository, String relativePath) {
        Path candidate = resolve(repository, relativePath);
        try {
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("文件不存在");
            }
            Path real = candidate.toRealPath();
            requireInside(real, repository);
            return real;
        } catch (SandboxException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("无法解析文件路径");
        }
    }

    public Path resolveForWrite(Path repository, String relativePath) {
        Path candidate = resolve(repository, relativePath);
        Path parent = candidate.getParent();
        try {
            if (parent == null) {
                throw invalid("写入文件的父目录不存在或越界");
            }
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(candidate)) {
                throw invalid("写入目标不能是符号链接");
            }
            validateParentChain(repository, parent);
            Files.createDirectories(parent);
            validateParentChain(repository, parent);
            return parent.resolve(candidate.getFileName());
        } catch (Exception exception) {
            if (exception instanceof SandboxException sandboxException) {
                throw sandboxException;
            }
            throw invalid("写入文件的父目录不存在或越界");
        }
    }

    private Path resolve(Path repository, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw invalid("文件路径必须是仓库内相对路径");
        }
        try {
            Path relative = Path.of(relativePath);
            if (relative.isAbsolute() || containsParentTraversal(relative)) {
                throw invalid("文件路径必须是仓库内相对路径");
            }
            Path candidate = repository.resolve(relative).normalize();
            requireInside(candidate, repository);
            return candidate;
        } catch (SandboxException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid("文件路径必须是仓库内相对路径");
        }
    }

    private boolean containsParentTraversal(Path path) {
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private void requireInside(Path path, Path repository) {
        if (!path.startsWith(repository)) {
            throw invalid("文件路径越过仓库边界");
        }
    }

    private void validateParentChain(Path repository, Path parent) {
        validateExistingChain(repository, parent);
    }

    /**
     * 在创建缺失目录前逐级检查已存在路径。拒绝任何符号链接，避免 Windows 挂载或 POSIX
     * 工作树上的 realpath 权限差异绕过仓库边界校验。
     */
    private void validateExistingChain(Path repository, Path path) {
        Path current = path;
        while (current != null && current.startsWith(repository)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw invalid("目录路径不能包含符号链接");
                }
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw invalid("目录路径的父级必须是目录");
                }
            }
            if (current.equals(repository)) {
                return;
            }
            current = current.getParent();
        }
        throw invalid("文件路径越过仓库边界");
    }

    private SandboxException invalid(String message) {
        return new SandboxException(UNPROCESSABLE_ENTITY, "TOOL_PATH_INVALID", message);
    }
}
