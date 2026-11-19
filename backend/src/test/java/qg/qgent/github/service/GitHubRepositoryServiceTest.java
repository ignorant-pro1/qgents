package qg.qgent.github.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qg.qgent.auth.service.TokenService;
import qg.qgent.github.client.GitHubAppClient;
import qg.qgent.github.config.GitHubAppProperties;
import qg.qgent.github.entity.*;
import qg.qgent.github.mapper.*;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.support.TestIds;

class GitHubRepositoryServiceTest {
    private final Long actor = TestIds.next();
    private final Long team = TestIds.next();
    private final Long project = TestIds.next();
    private final GitHubInstallationMapper installations = mock(GitHubInstallationMapper.class);
    private final GitHubInstallationStateMapper states = mock(GitHubInstallationStateMapper.class);
    private final ProjectRepositoryMapper repositories = mock(ProjectRepositoryMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final GitHubAppClient app = mock(GitHubAppClient.class);
    private final GitHubAppProperties properties = new GitHubAppProperties();
    private final TokenService tokens =
            new TokenService("01234567890123456789012345678901", 15, 30, 30);
    private final GitHubRepositoryService service =
            new GitHubRepositoryService(
                    installations, states, repositories, projects, access, app, properties, tokens);

    @BeforeEach
    void setup() {
        for (Class<?> type :
                List.of(
                        GitHubInstallationEntity.class,
                        GitHubInstallationStateEntity.class,
                        ProjectRepositoryEntity.class)) {
            if (TableInfoHelper.getTableInfo(type) == null)
                TableInfoHelper.initTableInfo(
                        new MapperBuilderAssistant(new MybatisConfiguration(), "github"), type);
        }
        when(access.isCanonicalTeamOwner(team, actor)).thenReturn(true);
        var entity = new ProjectEntity();
        entity.setId(project);
        entity.setTeamId(team);
        when(projects.selectById(project)).thenReturn(entity);
        properties.setSlug("qgents");
    }

    @Test
    void installationStartsWithExpiringOpaqueStateAndRequiresOwner() {
        var result = service.createInstallationUrl(actor, team, "MOBILE");
        assertThat(result.installationUrl())
                .startsWith("https://github.com/apps/qgents/installations/new?")
                .contains("state=")
                .doesNotContain("redirect_uri");
        var state = org.mockito.ArgumentCaptor.forClass(GitHubInstallationStateEntity.class);
        verify(states).insert(state.capture());
        assertThat(state.getValue().getStateHash()).hasSize(32);
        assertThat(state.getValue().getActorId()).isEqualTo(actor);
        assertThat(state.getValue().getClient()).isEqualTo("MOBILE");
        assertThat(result.expiresAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC));
        when(access.isCanonicalTeamOwner(team, actor)).thenReturn(false);
        assertThatThrownBy(() -> service.createInstallationUrl(actor, team, "WEB"))
                .hasMessage("需要团队所有者权限");
    }

    @Test
    void callbackConsumesStateAndRepeatedCallbackDoesNotRelink() {
        var state = state();
        when(states.selectOne(any())).thenReturn(state);
        when(app.installation(12L))
                .thenReturn(new GitHubAppClient.Installation(12L, "owner", "Organization", false));
        var result = service.callback(12L, "state", null);
        assertThat(result.teamId()).isEqualTo(team);
        assertThat(result.client()).isEqualTo("MOBILE");
        assertThat(state.getUsedAt()).isNotNull();
        verify(installations).insert(any(GitHubInstallationEntity.class));
        assertThatThrownBy(() -> service.callback(12L, "state", null))
                .hasMessageContaining("已使用或过期");
        verify(app).installation(12L);
    }

    @Test
    void expiredStateAndCrossTeamInstallationAreRejected() {
        var state = state();
        state.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(states.selectOne(any())).thenReturn(state);
        assertThatThrownBy(() -> service.callback(12L, "state", null)).hasMessageContaining("过期");
        verifyNoInteractions(app);
        state.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        var connected = installation();
        connected.setTeamId(TestIds.next());
        when(installations.selectOne(any())).thenReturn(connected);
        assertThatThrownBy(() -> service.callback(12L, "state", null)).hasMessage("此安装已连接其他团队");
        verifyNoInteractions(app);
    }

    @Test
    void importingRepositoryStoresMetadataAndRepeatedImportReturnsSameRecord() {
        var installation = installation();
        when(installations.selectById(installation.getId())).thenReturn(installation);
        when(app.repositories(12L)).thenReturn(List.of(remote()));
        var imported = service.importRepository(actor, project, installation.getId(), 34L);
        assertThat(imported.getFullName()).isEqualTo("owner/repo");
        assertThat(imported.getDefaultBranch()).isEqualTo("main");
        assertThat(imported.getProjectId()).isEqualTo(project);
        when(repositories.selectOne(any())).thenReturn(imported);
        assertThat(service.importRepository(actor, project, installation.getId(), 34L))
                .isSameAs(imported);
        verify(repositories).insert(any(ProjectRepositoryEntity.class));
        verify(access, times(2)).requireProjectAdmin(project, actor);
    }

    @Test
    void importingRejectsCrossTeamAndUnauthorisedRepositories() {
        var installation = installation();
        installation.setTeamId(TestIds.next());
        when(installations.selectById(installation.getId())).thenReturn(installation);
        assertThatThrownBy(
                        () -> service.importRepository(actor, project, installation.getId(), 34L))
                .hasMessageContaining("不属于团队");
        verifyNoInteractions(app);
        installation.setTeamId(team);
        when(app.repositories(12L)).thenReturn(List.of());
        assertThatThrownBy(
                        () -> service.importRepository(actor, project, installation.getId(), 34L))
                .hasMessageContaining("授权范围");
        verify(repositories, never()).insert(any(ProjectRepositoryEntity.class));
    }

    @Test
    void agentGetsFreshRepositoryScopedTokensAndCannotUseUnimportedRepository() {
        var installation = installation();
        var imported = new ProjectRepositoryEntity();
        imported.setInstallationId(installation.getId());
        imported.setProviderRepositoryId(34L);
        when(repositories.selectOne(any())).thenReturn(imported);
        when(installations.selectById(installation.getId())).thenReturn(installation);
        when(app.installationToken(12L, 34L)).thenReturn("first", "renewed");
        var first = service.repositoryToken(actor, project, "owner/repo");
        assertThat(first.token()).isEqualTo("first");
        assertThat(first.providerRepositoryId()).isEqualTo(34L);
        assertThat(service.repositoryToken(actor, project, "owner/repo").token())
                .isEqualTo("renewed");
        when(repositories.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.repositoryToken(actor, project, "owner/other"))
                .hasMessageContaining("先将目标仓库导入");
        verify(app, times(2)).installationToken(12L, 34L);
    }

    @Test
    void syncingUpdatesRenamedRepositoryAndRemovesRevokedImport() {
        var installation = installation();
        when(installations.selectById(installation.getId())).thenReturn(installation);
        when(app.installation(12L))
                .thenReturn(new GitHubAppClient.Installation(12L, "owner", "Organization", false));
        when(app.repositories(12L)).thenReturn(List.of(remote()));
        var keep = new ProjectRepositoryEntity();
        keep.setId(TestIds.next());
        keep.setProviderRepositoryId(34L);
        keep.setFullName("owner/old-name");
        var revoked = new ProjectRepositoryEntity();
        revoked.setId(TestIds.next());
        revoked.setProviderRepositoryId(35L);
        when(repositories.selectList(any())).thenReturn(List.of(keep, revoked));
        service.sync(actor, team, installation.getId());
        assertThat(keep.getFullName()).isEqualTo("owner/repo");
        verify(repositories).updateById(keep);
        verify(repositories).deleteById(revoked.getId());
    }

    @Test
    void projectCatalogListsImportsByProviderIdAndKeepsConnectionStatus() {
        var installation = installation();
        when(installations.selectList(any())).thenReturn(List.of(installation));
        var second = new ProjectRepositoryEntity();
        second.setProviderRepositoryId(35L);
        second.setFullName("owner/second");
        var first = new ProjectRepositoryEntity();
        first.setProviderRepositoryId(34L);
        first.setFullName("owner/first");
        first.setDefaultBranch("main");
        first.setPrivateRepository(true);
        when(repositories.selectList(any())).thenReturn(List.of(second, first));

        var catalog = service.catalog(actor, project, false);

        assertThat(catalog.connectionStatus()).isEqualTo("ACTIVE");
        assertThat(catalog.items())
                .extracting(GitHubRepositoryService.CatalogRepository::providerRepositoryId)
                .containsExactly(34L, 35L);
        assertThat(catalog.items()).allMatch(GitHubRepositoryService.CatalogRepository::imported);
        verify(access).requireProjectMember(project, actor);
        verify(access, never()).requireTeamMember(any(), any());
        verifyNoInteractions(app);
    }

    @Test
    void teamCatalogMergesInstallationsDeduplicatesAndMarksImports() {
        var active = installation();
        var second = installation();
        second.setId(TestIds.next());
        second.setProviderInstallationId(13L);
        when(installations.selectList(any())).thenReturn(List.of(active, second));
        when(app.repositories(12L))
                .thenReturn(
                        List.of(
                                new GitHubAppClient.Repository(
                                        35L,
                                        "owner/shared",
                                        "main",
                                        "https://github.com/owner/shared",
                                        false),
                                remote()));
        when(app.repositories(13L))
                .thenReturn(
                        List.of(
                                new GitHubAppClient.Repository(
                                        35L,
                                        "owner/shared",
                                        "main",
                                        "https://github.com/owner/shared",
                                        false),
                                new GitHubAppClient.Repository(
                                        36L,
                                        "owner/only-second",
                                        "dev",
                                        "https://github.com/owner/only-second",
                                        true)));
        var imported = new ProjectRepositoryEntity();
        imported.setProviderRepositoryId(34L);
        when(repositories.selectList(any())).thenReturn(List.of(imported));

        var catalog = service.catalog(actor, project, true);

        assertThat(catalog.connectionStatus()).isEqualTo("ACTIVE");
        assertThat(catalog.items())
                .extracting(GitHubRepositoryService.CatalogRepository::providerRepositoryId)
                .containsExactly(34L, 35L, 36L);
        assertThat(catalog.items())
                .filteredOn(item -> item.providerRepositoryId() == 34L)
                .singleElement()
                .satisfies(item -> assertThat(item.imported()).isTrue());
        assertThat(catalog.items())
                .filteredOn(item -> item.providerRepositoryId() == 35L)
                .singleElement()
                .satisfies(item -> assertThat(item.imported()).isFalse());
        verify(access).requireTeamMember(team, actor);
    }

    @Test
    void teamCatalogSkipsSuspendedInstallationsAndReportsStatus() {
        var suspended = installation();
        suspended.setSuspended(true);
        when(installations.selectList(any())).thenReturn(List.of(suspended));
        when(repositories.selectList(any())).thenReturn(List.of());

        var catalog = service.catalog(actor, project, true);

        assertThat(catalog.connectionStatus()).isEqualTo("ALL_SUSPENDED");
        assertThat(catalog.items()).isEmpty();
        verifyNoInteractions(app);
    }

    @Test
    void teamCatalogDistinguishesPartiallySuspendedAndDisconnected() {
        var active = installation();
        var suspended = installation();
        suspended.setId(TestIds.next());
        suspended.setSuspended(true);
        when(installations.selectList(any())).thenReturn(List.of(active, suspended));
        when(app.repositories(12L)).thenReturn(List.of(remote()));
        when(repositories.selectList(any())).thenReturn(List.of());
        assertThat(service.catalog(actor, project, true).connectionStatus())
                .isEqualTo("PARTIALLY_SUSPENDED");

        when(installations.selectList(any())).thenReturn(List.of());
        assertThat(service.catalog(actor, project, true).connectionStatus())
                .isEqualTo("NOT_CONNECTED");
    }

    @Test
    void teamCatalogFailsWholeListWhenOneInstallationFails() {
        var active = installation();
        var second = installation();
        second.setId(TestIds.next());
        second.setProviderInstallationId(13L);
        when(installations.selectList(any())).thenReturn(List.of(active, second));
        when(app.repositories(12L)).thenReturn(List.of(remote()));
        when(app.repositories(13L))
                .thenThrow(new qg.qgent.github.client.GitHubApiException(502, null, false));

        assertThatThrownBy(() -> service.catalog(actor, project, true))
                .isInstanceOf(qg.qgent.github.client.GitHubApiException.class);
    }

    @Test
    void tokenErrorsUseStableCodes() {
        when(repositories.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.repositoryToken(actor, project, "owner/repo"))
                .isInstanceOfSatisfying(
                        qg.qgent.common.web.ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("REPOSITORY_NOT_IMPORTED"));
        var installation = installation();
        installation.setSuspended(true);
        var imported = new ProjectRepositoryEntity();
        imported.setInstallationId(installation.getId());
        when(repositories.selectOne(any())).thenReturn(imported);
        when(installations.selectById(installation.getId())).thenReturn(installation);
        assertThatThrownBy(() -> service.repositoryToken(actor, project, "owner/repo"))
                .isInstanceOfSatisfying(
                        qg.qgent.common.web.ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INSTALLATION_SUSPENDED"));
        verify(app, never()).installationToken(any(Long.class), any());
    }

    private GitHubInstallationEntity installation() {
        var result = new GitHubInstallationEntity();
        result.setId(TestIds.next());
        result.setTeamId(team);
        result.setProviderInstallationId(12L);
        result.setSuspended(false);
        return result;
    }

    private GitHubInstallationStateEntity state() {
        var state = new GitHubInstallationStateEntity();
        state.setId(TestIds.next());
        state.setActorId(actor);
        state.setTeamId(team);
        state.setClient("MOBILE");
        state.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        return state;
    }

    private GitHubAppClient.Repository remote() {
        return new GitHubAppClient.Repository(
                34L, "owner/repo", "main", "https://github.com/owner/repo", true);
    }
}
