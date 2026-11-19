package qg.qgent.sandbox.filesystem.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.sandbox.exception.SandboxException;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/**
 * 将 Workspace 存储标识解析为项目目录内的路径。
 * <p>
 * 标识格式为 {@code <projectId>/workspaces/<default|workspaceId>}，与目录布局一一对应；
 * 本地路径供后端读写，Docker 宿主机路径供 bind mount，两者指向同一份数据。
 */
@Component
@RequiredArgsConstructor
public class WorkspacePathResolver {
    private static final Pattern STORAGE_KEY =
        Pattern.compile("(\\d{1,19})/workspaces/(default|\\d{1,19})");

    private final ProjectDirectoryService projects;

    /**
     * 当前 Workspace 目录。
     */
    public Path resolveLocal(String storageKey) {
        Match match = parse(storageKey);
        return projects.workspace(match.projectId(), match.directory());
    }

    /**
     * 项目共享 Git 数据目录。
     */
    public Path resolveLocalGit(String storageKey) {
        return projects.gitRoot(parse(storageKey).projectId());
    }

    /**
     * 同一 Workspace 目录在 Docker 宿主机上的路径。
     */
    public Path resolveDockerHost(String storageKey) {
        Match match = parse(storageKey);
        return projects.dockerHostProjectRoot(match.projectId())
            .resolve("workspaces")
            .resolve(match.directory());
    }

    /**
     * 项目共享 Git 数据在 Docker 宿主机上的路径。
     */
    public Path resolveDockerHostGit(String storageKey) {
        return projects.dockerHostProjectRoot(parse(storageKey).projectId()).resolve(".git");
    }

    /**
     * 容器内 Workspace 目录的相对位置，例如 {@code workspaces/default}。
     */
    public String relativeDirectory(String storageKey) {
        return "workspaces/" + parse(storageKey).directory();
    }

    private Match parse(String storageKey) {
        Matcher matcher = STORAGE_KEY.matcher(storageKey == null ? "" : storageKey);
        if (!matcher.matches()) {
            throw new SandboxException(
                UNPROCESSABLE_ENTITY, "WORKSPACE_PATH_INVALID", "Workspace 存储标识不合法");
        }
        return new Match(Long.valueOf(matcher.group(1)), matcher.group(2));
    }

    private record Match(Long projectId, String directory) {
    }
}
