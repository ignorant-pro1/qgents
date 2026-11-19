package qg.qgent.sandbox.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.HostConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.dto.CreateSandboxRequest;
import qg.qgent.sandbox.filesystem.service.WorkspacePathResolver;
import qg.qgent.support.TestIds;

class DockerContainerRuntimeTest {
    @TempDir Path root;

    @Test
    void unavailableDockerDoesNotPreventApplicationStartup() {
        DockerClient docker = mock(DockerClient.class);
        when(docker.listContainersCmd()).thenThrow(new IllegalStateException("Docker unavailable"));
        var runtime =
                new DockerContainerRuntime(
                        docker,
                        new SandboxProperties(),
                        mock(WorkspacePathResolver.class),
                        mock(SandboxBindFactory.class));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(runtime::recoverManagedContainers);
    }

    @Test
    void tmpfsMountsIncludeBuildCachesAndUseConfiguredSizes() {
        SandboxProperties properties = new SandboxProperties();
        properties.setMavenCacheSize("1g");
        properties.setGradleCacheSize("3g");
        properties.setNpmCacheSize("512m");
        Map<String, String> tmpfs = runtime(properties).tmpfsMounts();

        assertEquals(9, tmpfs.size());
        assertEquals("rw,noexec,nosuid,size=512m", tmpfs.get("/tmp"));
        assertEquals("rw,noexec,nosuid,size=512m", tmpfs.get("/var/tmp"));
        assertEquals("rw,noexec,nosuid,size=64m", tmpfs.get("/run"));
        assertEquals(
                "rw,nosuid,nodev,uid=10001,gid=10001,mode=700,size=8g",
                tmpfs.get("/home/developer"));
        assertEquals(
                "rw,exec,nosuid,nodev,uid=10001,gid=10001,mode=700,size=1g",
                tmpfs.get("/home/developer/.m2"));
        assertEquals(
                "rw,exec,nosuid,nodev,uid=10001,gid=10001,mode=700,size=3g",
                tmpfs.get("/home/developer/.gradle"));
        assertEquals(
                "rw,nosuid,nodev,uid=10001,gid=10001,mode=700,size=512m",
                tmpfs.get("/home/developer/.npm"));
        assertEquals(
                "rw,exec,nosuid,nodev,uid=10001,gid=10001,mode=700,size=512m",
                tmpfs.get("/home/developer/.cache"));
        assertEquals(
                "rw,exec,nosuid,nodev,uid=10001,gid=10001,mode=700,size=1g",
                tmpfs.get("/opt/pnpm"));
    }

    @Test
    void createUsesWritableRootfsAndPreservesUserAndResourceLimits() throws Exception {
        Path localRoot = Files.createDirectory(root.resolve("local"));
        Long projectId = TestIds.next();
        String storageKey = projectId + "/workspaces/default";
        Files.createDirectories(localRoot.resolve(projectId + "/workspaces/default"));
        SandboxProperties properties = new SandboxProperties();
        var directories =
                new qg.qgent.project.storage.ProjectDirectoryService(
                        localRoot.toString(), root.resolve("host").toString());

        DockerClient docker = mock(DockerClient.class);
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(response.getId()).thenReturn("container-1");
        when(createCmd.exec()).thenReturn(response);
        when(createCmd.withName(any())).thenReturn(createCmd);
        when(createCmd.withUser(any())).thenReturn(createCmd);
        when(createCmd.withWorkingDir(any())).thenReturn(createCmd);
        when(createCmd.withLabels(any())).thenReturn(createCmd);
        when(createCmd.withHostConfig(any(HostConfig.class))).thenReturn(createCmd);
        when(createCmd.withCmd("sleep", "infinity")).thenReturn(createCmd);
        when(docker.createContainerCmd(anyString())).thenReturn(createCmd);
        when(docker.startContainerCmd(anyString())).thenReturn(startCmd);

        DockerContainerRuntime runtime =
                new DockerContainerRuntime(
                        docker,
                        properties,
                        new WorkspacePathResolver(directories),
                        new SandboxBindFactory(new WorkspacePathResolver(directories)));

        CreateSandboxRequest request = new CreateSandboxRequest();
        request.setSandboxId(java.util.UUID.randomUUID());
        request.setAgentRunId(TestIds.next());
        request.setAgentRunId(TestIds.next());
        request.setWorkspaceStorageKey(storageKey);
        request.setImageProfile("dev-tools");
        SandboxAllocation allocation =
                new SandboxAllocation(
                        request.getSandboxId(),
                        request.getAgentRunId(),
                        request.getWorkspaceStorageKey(),
                        request.getImageProfile(),
                        "READY",
                        "DOCKER",
                        Instant.EPOCH,
                        Instant.EPOCH,
                        Instant.EPOCH,
                        Instant.EPOCH,
                        Duration.ofMinutes(1),
                        null);

        runtime.create(request, allocation);

        verify(createCmd).withUser("10001:10001");
        ArgumentCaptor<Map<String, String>> labelsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(createCmd).withLabels(labelsCaptor.capture());
        assertEquals(
                request.getAgentRunId().toString(),
                labelsCaptor.getValue().get("qgents.agent-run-id"));
        ArgumentCaptor<HostConfig> hostConfigCaptor = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfigCaptor.capture());
        HostConfig hostConfig = hostConfigCaptor.getValue();
        assertFalse(hostConfig.getReadonlyRootfs());
        assertEquals(properties.getMemoryBytes(), hostConfig.getMemory());
        assertEquals(properties.getNanoCpus(), hostConfig.getNanoCPUs());
        assertEquals(properties.getPidsLimit(), hostConfig.getPidsLimit());
        assertEquals(
                "rw,nosuid,nodev,uid=10001,gid=10001,mode=700,size=8g",
                hostConfig.getTmpFs().get("/home/developer"));
        assertEquals("rw,noexec,nosuid,size=512m", hostConfig.getTmpFs().get("/var/tmp"));
        assertEquals(
                "rw,exec,nosuid,nodev,uid=10001,gid=10001,mode=700,size=2g",
                hostConfig.getTmpFs().get("/home/developer/.m2"));
        assertEquals(
                "rw,exec,nosuid,nodev,uid=10001,gid=10001,mode=700,size=3g",
                hostConfig.getTmpFs().get("/home/developer/.gradle"));
        assertEquals(
                "rw,nosuid,nodev,uid=10001,gid=10001,mode=700,size=1g",
                hostConfig.getTmpFs().get("/home/developer/.npm"));
        assertEquals(
                "rw,exec,nosuid,nodev,uid=10001,gid=10001,mode=700,size=512m",
                hostConfig.getTmpFs().get("/home/developer/.cache"));
        assertEquals(
                "rw,exec,nosuid,nodev,uid=10001,gid=10001,mode=700,size=1g",
                hostConfig.getTmpFs().get("/opt/pnpm"));
    }

    private DockerContainerRuntime runtime(SandboxProperties properties) {
        WorkspacePathResolver paths =
                new WorkspacePathResolver(
                        new qg.qgent.project.storage.ProjectDirectoryService(
                                root.toString(), root.toString()));
        return new DockerContainerRuntime(
                mock(DockerClient.class), properties, paths, new SandboxBindFactory(paths));
    }
}
