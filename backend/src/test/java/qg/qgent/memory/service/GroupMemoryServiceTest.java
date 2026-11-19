package qg.qgent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import qg.qgent.chat.service.GroupService;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.dto.GroupMemoryResponse;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.support.TestIds;

/** 群记忆文件：按项目和群隔离、只读入口同时校验项目管理员与来源群成员。 */
class GroupMemoryServiceTest {

    private static final int MAX_CHARS = 100;

    @TempDir Path root;

    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final GroupService groups = mock(GroupService.class);
    private GroupMemoryService service;

    @BeforeEach
    void setUp() {
        service =
                new GroupMemoryService(
                        access,
                        groups,
                        new ProjectDirectoryService(root.toString(), root.toString()),
                        new MemoryDocument(MAX_CHARS));
    }

    @Test
    void groupsAndProjectsKeepSeparateMemoryFiles() throws Exception {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long otherGroup = TestIds.next();
        Long otherProject = TestIds.next();

        service.write(projectId, groupId, "# 登录改造\n- 结论：先做邮箱登录");

        assertThat(service.read(projectId, groupId).getContent()).isEqualTo("# 登录改造\n- 结论：先做邮箱登录");
        assertThat(
                        Files.readString(
                                root.resolve(projectId.toString())
                                        .resolve("groups")
                                        .resolve(groupId.toString())
                                        .resolve("memory.md"),
                                StandardCharsets.UTF_8))
                .startsWith("# 登录改造");
        assertThat(service.read(projectId, otherGroup).getContent()).isEmpty();
        assertThat(service.read(otherProject, groupId).getContent()).isEmpty();
    }

    @Test
    void unrefinedGroupReadsEmptyMemoryWithoutCreatingFile() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();

        GroupMemoryResponse memory = service.read(projectId, groupId);

        assertThat(memory.getContent()).isEmpty();
        assertThat(memory.getUpdatedAt()).isNull();
        assertThat(memory.getGroupId()).isEqualTo(groupId.toString());
        assertThat(
                        root.resolve(projectId.toString())
                                .resolve("groups")
                                .resolve(groupId.toString())
                                .resolve("memory.md"))
                .doesNotExist();
    }

    @Test
    void adminReadRequiresProjectAdminThenGroupMembership() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long actor = TestIds.next();
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"))
                .when(access)
                .requireProjectAdmin(projectId, actor);

        assertThatThrownBy(() -> service.get(actor, projectId, groupId))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("PROJECT_ADMIN_REQUIRED");
        verify(groups, never()).requireGroupMember(projectId, groupId, actor);
    }

    @Test
    void adminOutsideSourceGroupCannotReadGroupMemory() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long actor = TestIds.next();
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问"))
                .when(groups)
                .requireGroupMember(projectId, groupId, actor);

        assertThatThrownBy(() -> service.get(actor, projectId, groupId))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("GROUP_NOT_FOUND");
    }

    @Test
    void adminGroupMemberReadsCurrentContent() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long actor = TestIds.next();
        service.write(projectId, groupId, "# 群记忆");

        GroupMemoryResponse memory = service.get(actor, projectId, groupId);

        assertThat(memory.getContent()).isEqualTo("# 群记忆");
        assertThat(memory.getProjectId()).isEqualTo(projectId.toString());
        verify(access).requireProjectAdmin(projectId, actor);
        verify(groups).requireGroupMember(projectId, groupId, actor);
    }
}
