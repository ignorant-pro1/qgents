package qg.qgent.skill.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import qg.qgent.common.web.GlobalExceptionHandler;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.skill.entity.SkillEntity;
import qg.qgent.skill.mapper.SkillMapper;
import qg.qgent.skill.service.SkillService;
import qg.qgent.support.TestIds;
import qg.qgent.user.mapper.UserMapper;

class SkillControllerTest {
    private final Long actor = TestIds.next(), project = TestIds.next();
    private final SkillMapper mapper = mock(SkillMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final SkillService service =
            new SkillService(mapper, access, mock(UserMapper.class), mock(EventService.class));
    private final org.springframework.test.web.servlet.MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new SkillController(service, new ObjectMapper()))
                    .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void previewsThenCreatesTheUploadedDocument() throws Exception {
        String content = "---\nname: example\ndescription: 测试用途\n---\n正文\n";
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "SKILL.md",
                        "text/markdown",
                        content.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/projects/{project}/skills/preview", project).file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("example"))
                .andExpect(jsonPath("$.data.content").value(content));
        verify(mapper, never()).insert(any(SkillEntity.class));

        SkillEntity[] inserted = new SkillEntity[1];
        when(mapper.insert(any(SkillEntity.class)))
                .thenAnswer(call -> {
                    inserted[0] = call.getArgument(0);
                    inserted[0].setId(TestIds.next());
                    return 1;
                });
        when(mapper.selectById(any())).thenAnswer(call -> inserted[0]);
        mvc.perform(
                        multipart("/api/v1/projects/{project}/skills", project)
                                .file(file)
                                .param("visibility", "PRIVATE")
                                .param("tags", "[\"java\"]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("example"))
                .andExpect(jsonPath("$.data.description").value("测试用途"))
                .andExpect(jsonPath("$.data.content").value(content))
                .andExpect(jsonPath("$.data.tags[0]").value("java"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        verify(access, times(2)).requireProjectMember(project, actor);
    }
}
