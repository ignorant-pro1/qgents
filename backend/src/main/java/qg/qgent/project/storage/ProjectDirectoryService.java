package qg.qgent.project.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 项目持久目录：一个项目一个目录，按项目 ID 命名。
 * <p>
 * 目录内 memory.md 是项目记忆，groups/ 保存每个群聊的群记忆，.git/ 保存各仓库共享的 bare Git 数据，
 * workspaces/ 保存每个 Workspace 的普通工作文件（每仓库一个 worktree）。
 */
@Component
public class ProjectDirectoryService {
    private final Path root;
    private final Path dockerHostRoot;

    public ProjectDirectoryService(
        @Value("${app.project.root}") String root,
        @Value("${app.project.docker-host-root}") String dockerHostRoot) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.dockerHostRoot = Path.of(dockerHostRoot).toAbsolutePath().normalize();
    }

    /**
     * 项目根目录，不存在时创建；已存在时保留原有文件。
     */
    public Path projectRoot(Long projectId) {
        return create(root.resolve(projectId.toString()));
    }

    /**
     * 项目内某个群聊的目录，群记忆等群级文件位于其中；不存在时创建。
     */
    public Path groupRoot(Long projectId, Long groupId) {
        return create(projectRoot(projectId).resolve("groups").resolve(groupId.toString()));
    }

    /**
     * 项目共享 Git 数据目录，不存在时创建。
     */
    public Path gitRoot(Long projectId) {
        return create(projectRoot(projectId).resolve(".git"));
    }

    /**
     * 单个仓库的 bare Git 数据目录。
     */
    public Path repositoryGit(Long projectId, Long providerRepositoryId) {
        return gitRoot(projectId).resolve(providerRepositoryId + ".git");
    }

    /**
     * Workspace 根目录，不存在时创建。
     */
    public Path workspacesRoot(Long projectId) {
        return create(projectRoot(projectId).resolve("workspaces"));
    }

    /**
     * 单个 Agent 的私有记忆目录；只在写入时创建，不挂载进沙箱。
     */
    public Path agentMemory(Long projectId, Long agentId) {
        return projectRoot(projectId).resolve("agent-memory").resolve(agentId.toString());
    }

    /**
     * 单个 Workspace 的目录，不存在时创建；directoryName 为 default 或 Workspace ID。
     */
    public Path workspace(Long projectId, String directoryName) {
        return create(workspacesRoot(projectId).resolve(directoryName));
    }

    /**
     * Workspace 内某仓库的 worktree 路径。
     */
    public Path worktree(Long projectId, String directoryName, Long providerRepositoryId) {
        return workspace(projectId, directoryName).resolve(providerRepositoryId.toString());
    }

    /**
     * 受管理仓库的准备记录；位于项目 Git 目录内，不参与 worktree。
     */
    public Path repositoryState(Long projectId, String directoryName, Long providerRepositoryId) {
        return gitRoot(projectId)
            .resolve("qgents")
            .resolve("workspaces")
            .resolve(directoryName)
            .resolve(providerRepositoryId + ".json");
    }

    /**
     * Docker daemon 所在宿主机上同一项目目录的路径，用于创建 bind mount。
     */
    public Path dockerHostProjectRoot(Long projectId) {
        return dockerHostRoot.resolve(projectId.toString());
    }

    /**
     * 删除项目目录及其全部文件；目录不存在时什么都不做。
     */
    public void delete(Long projectId) {
        Path directory = root.resolve(projectId.toString());
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        } catch (NoSuchFileException ignored) {
            // 项目从未写过文件，目录不存在
        } catch (IOException e) {
            throw new IllegalStateException("项目目录删除失败: " + directory, e);
        }
    }

    private Path create(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("项目目录创建失败: " + directory, e);
        }
        return directory;
    }
}
