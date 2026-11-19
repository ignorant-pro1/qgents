package qg.qgent.sandbox.runtime;

import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandbox.filesystem.service.WorkspacePathResolver;

import java.util.List;

/**
 * 挂载项目共享 Git 数据、当前 Workspace 和它的 /workspace 别名。
 * <p>
 * 其他 Workspace 的普通工作文件不挂载；共享 Git 数据是同一项目各 worktree 的既定共同存储，
 * 不能据此宣称 Workspace 之间是互相独立的 Git 仓库或严格安全隔离。
 */
@Component
@RequiredArgsConstructor
public class SandboxBindFactory {
    private final WorkspacePathResolver paths;

    public List<Bind> create(SandboxAllocation allocation) {
        String key = allocation.getWorkspaceStorageKey();
        return List.of(
            new Bind(
                paths.resolveDockerHostGit(key).toString(),
                new Volume("/qgents/project/.git"),
                AccessMode.rw),
            new Bind(
                paths.resolveDockerHost(key).toString(),
                new Volume("/qgents/project/" + paths.relativeDirectory(key)),
                AccessMode.rw),
            new Bind(
                paths.resolveDockerHost(key).toString(),
                new Volume("/workspace"),
                AccessMode.rw));
    }
}
