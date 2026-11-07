package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.ProjectGitRepo;
import com.bu.management.mapper.ProjectGitRepoMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 仓库绑定校验：只收 https、不许带凭据、agent 白名单、work_dir 归一、重复绑定拒绝。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectGitRepoServiceTest {

    private static final String VALID_URL = "https://git.example.com/bu/project-x.git";

    @Mock private ProjectGitRepoMapper gitRepoMapper;

    private ProjectGitRepoService service;

    @BeforeEach
    void setUp() {
        service = new ProjectGitRepoService(gitRepoMapper);
        when(gitRepoMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void rejectsNonHttpsUrl() {
        assertThatThrownBy(() -> ProjectGitRepoService.normalizeUrl("http://git.example.com/bu/x.git"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://");
        assertThatThrownBy(() -> ProjectGitRepoService.normalizeUrl("git@github.com:bu/x.git"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProjectGitRepoService.normalizeUrl("ssh://git@host/bu/x.git"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProjectGitRepoService.normalizeUrl("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThat(ProjectGitRepoService.normalizeUrl("  " + VALID_URL + "  ")).isEqualTo(VALID_URL);
    }

    @Test
    void rejectsCredentialInUrl() {
        assertThatThrownBy(() -> ProjectGitRepoService.normalizeUrl(
                "https://user:{{API_TOKEN_f7a2b1c9}}@git.example.com/bu/x.git"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentialRef");
    }

    @Test
    void rejectsOverlongUrl() {
        String longUrl = "https://git.example.com/" + "a".repeat(ProjectGitRepoService.MAX_URL_LENGTH);
        assertThatThrownBy(() -> ProjectGitRepoService.normalizeUrl(longUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上限");
    }

    @Test
    void agentWhitelistAndDefaults() {
        assertThat(ProjectGitRepoService.normalizeAgent(null)).isEqualTo("dsh");
        assertThat(ProjectGitRepoService.normalizeAgent("  ")).isEqualTo("dsh");
        assertThat(ProjectGitRepoService.normalizeAgent("Claude-Code")).isEqualTo("claude-code");
        assertThat(ProjectGitRepoService.normalizeAgent("CODEX")).isEqualTo("codex");
        assertThat(ProjectGitRepoService.normalizeAgent("command")).isEqualTo("command");
        assertThatThrownBy(() -> ProjectGitRepoService.normalizeAgent("bash"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的 agent");
    }

    @Test
    void workDirNormalized() {
        assertThat(ProjectGitRepoService.normalizeWorkDir(null)).isNull();
        assertThat(ProjectGitRepoService.normalizeWorkDir("")).isNull();
        assertThat(ProjectGitRepoService.normalizeWorkDir("/")).isNull();
        assertThat(ProjectGitRepoService.normalizeWorkDir("sql/")).isEqualTo("sql");
        assertThat(ProjectGitRepoService.normalizeWorkDir("/sql/docs/")).isEqualTo("sql/docs");
        assertThat(ProjectGitRepoService.normalizeWorkDir("\\sql\\api\\")).isEqualTo("sql/api");
        assertThatThrownBy(() -> ProjectGitRepoService.normalizeWorkDir("../etc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("..");
        assertThatThrownBy(() -> ProjectGitRepoService.normalizeWorkDir("sql/../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveInsertsWithDefaults() {
        when(gitRepoMapper.insert(any(ProjectGitRepo.class))).thenReturn(1);

        ProjectGitRepo saved = service.save(11L, new ProjectGitRepoService.RepoRequest(
                null, null, " " + VALID_URL + " ", null, "/sql/", "Claude-Code", null, null));

        ArgumentCaptor<ProjectGitRepo> captor = ArgumentCaptor.forClass(ProjectGitRepo.class);
        verify(gitRepoMapper).insert(captor.capture());
        ProjectGitRepo inserted = captor.getValue();
        assertThat(inserted.getProjectId()).isEqualTo(11L);
        assertThat(inserted.getRepoUrl()).isEqualTo(VALID_URL);
        // name 为空时用 repoUrl
        assertThat(inserted.getName()).isEqualTo(VALID_URL);
        assertThat(inserted.getDefaultBranch()).isEqualTo("main");
        assertThat(inserted.getWorkDir()).isEqualTo("sql");
        assertThat(inserted.getAgent()).isEqualTo("claude-code");
        assertThat(inserted.getCredentialRef()).isNull();
        assertThat(inserted.getEnabled()).isEqualTo(1);
        assertThat(inserted.getCreatedAt()).isNotNull();
        assertThat(inserted.getUpdatedAt()).isNotNull();
        assertThat(saved.getRepoUrl()).isEqualTo(VALID_URL);
    }

    @Test
    void saveHonorsExplicitValues() {
        when(gitRepoMapper.insert(any(ProjectGitRepo.class))).thenReturn(1);

        service.save(11L, new ProjectGitRepoService.RepoRequest(
                null, "皇家项目仓库", VALID_URL, "release", null, "dsh", "gitlab:huangjia", false));

        ArgumentCaptor<ProjectGitRepo> captor = ArgumentCaptor.forClass(ProjectGitRepo.class);
        verify(gitRepoMapper).insert(captor.capture());
        ProjectGitRepo inserted = captor.getValue();
        assertThat(inserted.getName()).isEqualTo("皇家项目仓库");
        assertThat(inserted.getDefaultBranch()).isEqualTo("release");
        assertThat(inserted.getWorkDir()).isNull();
        assertThat(inserted.getCredentialRef()).isEqualTo("gitlab:huangjia");
        assertThat(inserted.getEnabled()).isZero();
    }

    @Test
    void rejectsDuplicateBinding() {
        ProjectGitRepo existing = new ProjectGitRepo();
        existing.setId(5L);
        existing.setProjectId(11L);
        existing.setRepoUrl(VALID_URL);
        when(gitRepoMapper.selectList(any())).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.save(11L, new ProjectGitRepoService.RepoRequest(
                null, "duplicate", VALID_URL, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("该仓库已绑定到本项目");
        verify(gitRepoMapper, never()).insert(any(ProjectGitRepo.class));
    }

    @Test
    void rejectsNonHttpsOnSave() {
        assertThatThrownBy(() -> service.save(11L, new ProjectGitRepoService.RepoRequest(
                null, "ssh", "git@github.com:bu/x.git", null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(gitRepoMapper, never()).insert(any(ProjectGitRepo.class));
    }
}
