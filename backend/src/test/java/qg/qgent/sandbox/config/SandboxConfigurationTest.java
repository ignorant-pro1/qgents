package qg.qgent.sandbox.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.sandbox.execution.mapper.ToolExecutionLogMapper;
import qg.qgent.sandbox.execution.mapper.ToolExecutionMapper;
import qg.qgent.sandbox.execution.service.ToolExecutionService;
import qg.qgent.sandbox.runtime.ContainerRuntime;
import qg.qgent.sandbox.runtime.FakeContainerRuntime;
import qg.qgent.sandbox.service.SandboxCommands;
import qg.qgent.sandbox.service.SandboxFiles;
import qg.qgent.sandbox.service.SandboxSessionManager;
import qg.qgent.workspace.mapper.WorkspaceMapper;
import qg.qgent.workspace.service.WorkspaceOperationLock;
import qg.qgent.workspace.service.WorkspaceWriteLeaseService;

class SandboxConfigurationTest {
    @TempDir Path directory;

    @Test
    void sandboxModuleUsesApplicationMapperAndDirectServices() {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context,
                    "sandbox.runtime=fake",
                    "app.project.root=" + directory,
                    "app.project.docker-host-root=" + directory);
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            context.registerBean(ObjectMapper.class, () -> mapper);
            context.registerBean(ToolExecutionMapper.class, () -> mock(ToolExecutionMapper.class));
            context.registerBean(
                    ToolExecutionLogMapper.class, () -> mock(ToolExecutionLogMapper.class));
            context.registerBean(WorkspaceMapper.class, () -> mock(WorkspaceMapper.class));
            context.registerBean(
                    WorkspaceWriteLeaseService.class, () -> mock(WorkspaceWriteLeaseService.class));
            context.register(WorkspaceOperationLock.class, ProjectDirectoryService.class);
            context.scan("qg.qgent.sandbox");
            context.refresh();

            assertThat(context.getBeansOfType(ObjectMapper.class).values()).containsExactly(mapper);
            assertThat(context.getBean(ContainerRuntime.class))
                    .isInstanceOf(FakeContainerRuntime.class);
            assertThat(context.getBean(ToolExecutionService.class)).isNotNull();
            assertThat(context.getBean(SandboxSessionManager.class)).isNotNull();
            assertThat(context.getBean(SandboxFiles.class)).isNotNull();
            assertThat(context.getBean(SandboxCommands.class)).isNotNull();
            assertThat(
                            context.getBeansWithAnnotation(
                                    org.springframework.web.bind.annotation.RestController.class))
                    .isEmpty();
        }
    }
}
