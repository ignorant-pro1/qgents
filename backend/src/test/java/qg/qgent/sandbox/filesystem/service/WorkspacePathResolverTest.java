package qg.qgent.sandbox.filesystem.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.sandbox.exception.SandboxException;

class WorkspacePathResolverTest {
    @TempDir Path temporaryDirectory;

    @Test
    void resolvesWorkspaceAndSharedGitDirectories() throws Exception {
        Path local = Files.createDirectory(temporaryDirectory.resolve("local"));
        Path host = Files.createDirectory(temporaryDirectory.resolve("host"));
        WorkspacePathResolver resolver =
                new WorkspacePathResolver(
                        new ProjectDirectoryService(local.toString(), host.toString()));

        assertEquals(
                local.resolve("7/workspaces/default"),
                resolver.resolveLocal("7/workspaces/default"));
        assertEquals(local.resolve("7/.git"), resolver.resolveLocalGit("7/workspaces/default"));
        assertEquals(
                host.resolve("7/workspaces/12"), resolver.resolveDockerHost("7/workspaces/12"));
        assertEquals(host.resolve("7/.git"), resolver.resolveDockerHostGit("7/workspaces/12"));
        assertEquals("workspaces/12", resolver.relativeDirectory("7/workspaces/12"));
    }

    @Test
    void rejectsMalformedStorageKeys() {
        WorkspacePathResolver resolver =
                new WorkspacePathResolver(
                        new ProjectDirectoryService(
                                temporaryDirectory.toString(), temporaryDirectory.toString()));

        assertThrows(SandboxException.class, () -> resolver.resolveLocal("7/../outside"));
        assertThrows(SandboxException.class, () -> resolver.resolveLocal("workspaces/default"));
        assertThrows(SandboxException.class, () -> resolver.resolveLocal("7/workspaces/../12"));
        assertThrows(SandboxException.class, () -> resolver.resolveLocal(null));
    }
}
