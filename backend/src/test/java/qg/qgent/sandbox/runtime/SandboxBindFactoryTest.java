package qg.qgent.sandbox.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import qg.qgent.sandbox.filesystem.service.WorkspacePathResolver;

class SandboxBindFactoryTest {
    @Test
    void mountsSharedGitCurrentWorkspaceAndLegacyAliasOnly() {
        var paths = mock(WorkspacePathResolver.class);
        var allocation = mock(SandboxAllocation.class);
        when(allocation.getWorkspaceStorageKey()).thenReturn("7/workspaces/default");
        when(paths.resolveDockerHostGit("7/workspaces/default"))
                .thenReturn(Path.of("/data/7/.git"));
        when(paths.resolveDockerHost("7/workspaces/default"))
                .thenReturn(Path.of("/data/7/workspaces/default"));
        when(paths.relativeDirectory("7/workspaces/default")).thenReturn("workspaces/default");

        List<Bind> binds = new SandboxBindFactory(paths).create(allocation);

        assertThat(binds)
                .extracting(bind -> bind.getVolume().getPath())
                .containsExactly(
                        "/qgents/project/.git", "/qgents/project/workspaces/default", "/workspace");
        assertThat(binds)
                .extracting(bind -> Path.of(bind.getPath()))
                .containsExactly(
                        Path.of("/data/7/.git"),
                        Path.of("/data/7/workspaces/default"),
                        Path.of("/data/7/workspaces/default"));
        assertThat(binds)
                .allSatisfy(bind -> assertThat(bind.getAccessMode()).isEqualTo(AccessMode.rw));
    }
}
