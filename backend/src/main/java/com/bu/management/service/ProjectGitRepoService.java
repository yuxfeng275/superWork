package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.ProjectGitRepo;
import com.bu.management.mapper.ProjectGitRepoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 项目绑定 HTTPS 代码仓库：列表、启用列表、保存（新增/更新）、解绑。
 *
 * <p>约束：只接受 https:// 地址，禁止把凭据写进 URL，work_dir 只能落在仓库内的相对目录。
 *
 * @author BU Team
 * @since 2026-05-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectGitRepoService {

    /** 允许的 agent 白名单。 */
    public static final Set<String> ALLOWED_AGENTS = Set.of("dsh", "command", "claude-code", "codex");

    public static final String DEFAULT_AGENT = "dsh";

    public static final String DEFAULT_BRANCH = "main";

    /** 仓库地址长度上限。 */
    public static final int MAX_URL_LENGTH = 500;

    private static final String HTTPS_PREFIX = "https://";

    private static final String AGENT_HINT = "dsh/command/claude-code/codex";

    private final ProjectGitRepoMapper gitRepoMapper;

    /** 保存请求体（同时用于新增与更新）。 */
    public record RepoRequest(
            Long id,
            String name,
            String repoUrl,
            String defaultBranch,
            String workDir,
            String agent,
            String credentialRef,
            Boolean enabled) {
    }

    /** 项目下全部绑定（含停用）。 */
    public List<ProjectGitRepo> list(Long projectId) {
        requireProject(projectId);
        return gitRepoMapper.selectList(new LambdaQueryWrapper<ProjectGitRepo>()
                .eq(ProjectGitRepo::getProjectId, projectId)
                .orderByAsc(ProjectGitRepo::getId));
    }

    /** 项目下启用中的绑定（夜间 agent 用）。 */
    public List<ProjectGitRepo> listEnabled(Long projectId) {
        requireProject(projectId);
        return gitRepoMapper.selectList(new LambdaQueryWrapper<ProjectGitRepo>()
                .eq(ProjectGitRepo::getProjectId, projectId)
                .eq(ProjectGitRepo::getEnabled, 1)
                .orderByAsc(ProjectGitRepo::getId));
    }

    /** 新增（id 为空）或更新（id 非空，且必须属于该项目）。 */
    @Transactional(rollbackFor = Exception.class)
    public ProjectGitRepo save(Long projectId, RepoRequest request) {
        requireProject(projectId);
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String repoUrl = normalizeUrl(request.repoUrl());
        String agent = normalizeAgent(request.agent());
        String workDir = normalizeWorkDir(request.workDir());
        String defaultBranch = isBlank(request.defaultBranch()) ? DEFAULT_BRANCH : request.defaultBranch().trim();
        String name = isBlank(request.name()) ? repoUrl : request.name().trim();
        String credentialRef = isBlank(request.credentialRef()) ? null : request.credentialRef().trim();

        // 同一项目同一 repoUrl 只允许绑定一次
        for (ProjectGitRepo existing : list(projectId)) {
            if (repoUrl.equals(existing.getRepoUrl()) && !Objects.equals(existing.getId(), request.id())) {
                throw new IllegalArgumentException("该仓库已绑定到本项目");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        if (request.id() == null) {
            ProjectGitRepo entity = new ProjectGitRepo();
            entity.setProjectId(projectId);
            entity.setName(name);
            entity.setRepoUrl(repoUrl);
            entity.setDefaultBranch(defaultBranch);
            entity.setWorkDir(workDir);
            entity.setAgent(agent);
            entity.setCredentialRef(credentialRef);
            entity.setEnabled(request.enabled() == null || request.enabled() ? 1 : 0);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            gitRepoMapper.insert(entity);
            log.info("项目 {} 绑定仓库 {}（agent={}，workDir={}）", projectId, repoUrl, agent, workDir);
            return entity;
        }

        ProjectGitRepo entity = gitRepoMapper.selectById(request.id());
        if (entity == null || !Objects.equals(entity.getProjectId(), projectId)) {
            throw new IllegalArgumentException("仓库绑定不存在或不属于该项目");
        }
        entity.setName(name);
        entity.setRepoUrl(repoUrl);
        entity.setDefaultBranch(defaultBranch);
        entity.setWorkDir(workDir);
        entity.setAgent(agent);
        entity.setCredentialRef(credentialRef);
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled() ? 1 : 0);
        }
        entity.setUpdatedAt(now);
        gitRepoMapper.updateById(entity);
        return entity;
    }

    /** 解绑；只能解绑本项目下的记录。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long projectId, Long id) {
        requireProject(projectId);
        if (id == null) {
            throw new IllegalArgumentException("仓库绑定 ID 不能为空");
        }
        ProjectGitRepo entity = gitRepoMapper.selectById(id);
        if (entity == null || !Objects.equals(entity.getProjectId(), projectId)) {
            throw new IllegalArgumentException("仓库绑定不存在或不属于该项目");
        }
        gitRepoMapper.deleteById(id);
    }

    // ==================== 校验（包级可见，便于单测） ====================

    /** 只接受 https:// 开头、不含凭据 @、长度不超过 {@link #MAX_URL_LENGTH} 的地址。 */
    static String normalizeUrl(String raw) {
        if (isBlank(raw)) {
            throw new IllegalArgumentException("仓库地址不能为空");
        }
        String url = raw.trim();
        if (!url.toLowerCase(Locale.ROOT).startsWith(HTTPS_PREFIX)) {
            throw new IllegalArgumentException("只支持 https:// 开头的仓库地址");
        }
        if (url.contains("@")) {
            throw new IllegalArgumentException("仓库地址不允许包含凭据(@)，请改用 credentialRef");
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("仓库地址过长（上限 " + MAX_URL_LENGTH + " 字符）");
        }
        return url;
    }

    /** 空默认 dsh，小写归一，必须在白名单内。 */
    static String normalizeAgent(String raw) {
        String agent = isBlank(raw) ? DEFAULT_AGENT : raw.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_AGENTS.contains(agent)) {
            throw new IllegalArgumentException("不支持的 agent：" + raw + "（可选 " + AGENT_HINT + "）");
        }
        return agent;
    }

    /** 反斜杠转正斜杠、去掉首尾斜杠，空串返回 null，含 .. 拒绝。 */
    static String normalizeWorkDir(String raw) {
        if (raw == null) {
            return null;
        }
        String dir = raw.trim().replace('\\', '/');
        while (dir.startsWith("/")) {
            dir = dir.substring(1);
        }
        while (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        if (dir.isEmpty()) {
            return null;
        }
        if (dir.contains("..")) {
            throw new IllegalArgumentException("工作目录不允许包含 ..");
        }
        return dir;
    }

    private static void requireProject(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("项目 ID 不能为空");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
