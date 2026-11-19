package qg.qgent.skill.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.skill.dto.SkillResponse;
import qg.qgent.skill.entity.SkillEntity;
import qg.qgent.skill.mapper.SkillMapper;
import qg.qgent.support.TestIds;
import qg.qgent.user.mapper.UserMapper;

/**
 * Project Admin 自建共享 Skill 免审批：
 * Admin 创建即 PUBLISHED 并发布事件；普通成员创建保持 DRAFT 且无上架事件。
 */
class SkillAdminAutoPublishTest {

    private final SkillMapper skillMapper = mock(SkillMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final EventService eventService = mock(EventService.class);

    private final SkillService skillService =
            new SkillService(skillMapper, access, userMapper, eventService);

    private final Long projectId = TestIds.next();
    private final Long admin = TestIds.next();
    private final Long member = TestIds.next();

    @Test
    void skillCreatedByAdminIsPublishedImmediately() throws Exception {
        when(access.isProjectAdmin(projectId, admin)).thenReturn(true);
        final SkillEntity[] created = new SkillEntity[1];
        when(skillMapper.insert(any(SkillEntity.class)))
                .thenAnswer(invocation -> {
                    created[0] = invocation.getArgument(0);
                    created[0].setId(TestIds.next());
                    return 1;
                });
        // selectById 返回 create 内部对象同一引用：create 对状态的上架修改对 response 可见
        when(skillMapper.selectById(any())).thenAnswer(invocation -> created[0]);

        SkillResponse response =
                skillService.create(admin, projectId, skillFile("规范"), "PROJECT_SHARED", List.of());

        assertEquals("PUBLISHED", response.getStatus());
        verify(skillMapper, never()).updateById(any(SkillEntity.class));
        verify(eventService).publish(eq(projectId), isNull(), eq("skill.published"), any(), any());
    }

    @Test
    void skillCreatedByMemberStaysDraftWhenProjectShared() throws Exception {
        when(access.isProjectAdmin(projectId, member)).thenReturn(false);
        SkillEntity entity = skillEntity();
        when(skillMapper.selectById(any())).thenReturn(entity);

        SkillResponse response =
                skillService.create(
                        member, projectId, skillFile("规范"), "PROJECT_SHARED", List.of());

        assertEquals("DRAFT", response.getStatus());
        verify(eventService, never()).publish(any(), any(), eq("skill.published"), any(), any());
    }

    @Test
    void privateSkillCreatedByMemberIsPublishedImmediately() throws Exception {
        // PRIVATE 仅创建者自己可用，不共享，任何人创建即生效无需审核
        when(access.isProjectAdmin(projectId, member)).thenReturn(false);
        final SkillEntity[] created = new SkillEntity[1];
        when(skillMapper.insert(any(SkillEntity.class)))
                .thenAnswer(invocation -> {
                    created[0] = invocation.getArgument(0);
                    created[0].setId(TestIds.next());
                    return 1;
                });
        when(skillMapper.selectById(any())).thenAnswer(invocation -> created[0]);

        SkillResponse response =
                skillService.create(member, projectId, skillFile("个人工具"), "PRIVATE", List.of());

        assertEquals("PUBLISHED", response.getStatus());
        verify(skillMapper, never()).updateById(any(SkillEntity.class));
        assertEquals("PRIVATE", response.getVisibility());
        verify(eventService).publish(eq(projectId), isNull(), eq("skill.published"), any(), any());
    }

    private MockMultipartFile skillFile(String name) {
        String content = "---\nname: " + name + "\ndescription: 测试用途\n---\n内容";
        return new MockMultipartFile(
                "file", "SKILL.md", "text/markdown", content.getBytes(StandardCharsets.UTF_8));
    }

    private SkillEntity skillEntity() {
        SkillEntity skill = new SkillEntity();
        skill.setId(TestIds.next());
        skill.setProjectId(projectId);
        skill.setCreatedBy(admin);
        skill.setName("规范");
        skill.setContent("内容");
        skill.setStatus("DRAFT");
        skill.setVisibility("PROJECT_SHARED");
        return skill;
    }
}
