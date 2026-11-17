package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.Meeting;
import com.bu.management.entity.MeetingSpeaker;
import com.bu.management.entity.Project;
import com.bu.management.entity.ProjectFact;
import com.bu.management.entity.ProjectMember;
import com.bu.management.entity.User;
import com.bu.management.mapper.MeetingSpeakerMapper;
import com.bu.management.mapper.ProjectFactMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.ProjectMemberMapper;
import com.bu.management.mapper.UserMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 会议自动归属：把「上传时没认领项目」的会议推到唯一确定的项目上。
 *
 * <p>三条规则择先命中，命中即返回，都拿不准就返回 null：
 * <ol>
 *   <li>标题术语：标题里出现某个项目的 name/fullPath/code（长度≥3、非通用词），且只有一个项目命中；</li>
 *   <li>负责人历史：会议上传人是若干项目的项目经理，其中最近有事实或最近更新的唯一一个；</li>
 *   <li>参与人共同所属：转写文本/说话人里的真名对应到系统用户，取这些人在项目成员表里项目的
 *       交集——即他们共同参与的那个唯一项目。查不到成员记录的人（同名外部人、未入成员表）
 *       不参与交集，既不算证据也不会把结果打成空。</li>
 * </ol>
 *
 * <p>只查不写：回写 {@code meeting.project_id} 由调用方（{@link ProjectFactService}）负责。
 * 宁可返回 null 也不猜——归属错了比没有归属更难纠正。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingProjectAttributionService {

    /** 术语过短（如「AI」级别）命中率全是噪音，直接不用。 */
    private static final int MIN_TERM_LENGTH = 3;
    /** 人名过短（单字）满篇都是，不能当证据。 */
    private static final int MIN_NAME_LENGTH = 2;

    /** 通用词：几乎每个项目标题里都会有，不能作为归属依据。 */
    private static final Set<String> GENERIC_TERMS = Set.of(
            "ai", "crm", "项目", "产品", "数据", "系统", "平台", "研发", "业务");

    private final ProjectMapper projectMapper;
    private final ProjectFactMapper factMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserMapper userMapper;
    private final MeetingSpeakerMapper meetingSpeakerMapper;

    /**
     * 给一个未归属的会议找项目。
     *
     * @return 唯一确定的项目 id；不确定返回 null（不写库）
     */
    public Long attribute(Meeting meeting) {
        if (meeting == null) {
            return null;
        }
        List<Project> projects = loadProjects();
        Long byTitle = matchByTitleTerm(meeting.getTitle(), projects);
        if (byTitle != null) {
            log.debug("会议 {} 按标题术语归属到项目 {}", meeting.getId(), byTitle);
            return byTitle;
        }
        Long byManager = matchByManagerHistory(meeting.getOwnerUserId(), projects);
        if (byManager != null) {
            log.debug("会议 {} 按负责人历史归属到项目 {}", meeting.getId(), byManager);
            return byManager;
        }
        Long byParticipants = matchByParticipants(meeting);
        if (byParticipants != null) {
            log.debug("会议 {} 按参与人交集归属到项目 {}", meeting.getId(), byParticipants);
        }
        return byParticipants;
    }

    // ==================== 规则1：标题术语 ====================

    private Long matchByTitleTerm(String title, List<Project> projects) {
        if (!StringUtils.hasText(title)) {
            return null;
        }
        String haystack = title.toLowerCase(Locale.ROOT);
        Set<Long> hits = new LinkedHashSet<>();
        for (Project project : projects) {
            if (project.getId() == null) {
                continue;
            }
            if (containsTerm(haystack, project.getName())
                    || containsTerm(haystack, project.getFullPath())
                    || containsTerm(haystack, project.getCode())) {
                hits.add(project.getId());
            }
        }
        // 只有一个项目命中才算数；两个以上说明标题太笼统，交给下一条规则。
        return hits.size() == 1 ? hits.iterator().next() : null;
    }

    private static boolean containsTerm(String haystack, String term) {
        if (!StringUtils.hasText(term)) {
            return false;
        }
        String value = term.trim().toLowerCase(Locale.ROOT);
        // 长度按字符算（中文项目名两个字也算短），并排掉通用词
        if (value.length() < MIN_TERM_LENGTH || GENERIC_TERMS.contains(value)) {
            return false;
        }
        return haystack.contains(value);
    }

    // ==================== 规则2：负责人历史 ====================

    private Long matchByManagerHistory(Long ownerUserId, List<Project> projects) {
        if (ownerUserId == null) {
            return null;
        }
        List<Project> managed = new ArrayList<>();
        for (Project project : projects) {
            if (project.getId() != null && ownerUserId.equals(project.getManagerId())) {
                managed.add(project);
            }
        }
        if (managed.isEmpty()) {
            return null;
        }
        if (managed.size() == 1) {
            return managed.get(0).getId();
        }
        // 管多个项目时看最近动静：最近有事实或最近更新的那个，且必须明显领先
        Project best = null;
        LocalDateTime bestAt = null;
        boolean tie = false;
        for (Project project : managed) {
            LocalDateTime at = lastActivityOf(project);
            if (at == null) {
                continue;
            }
            if (bestAt == null || at.isAfter(bestAt)) {
                best = project;
                bestAt = at;
                tie = false;
            } else if (at.isEqual(bestAt)) {
                tie = true;
            }
        }
        if (best == null || tie) {
            return null;
        }
        return best.getId();
    }

    /** 项目的最后动静：事实更新时间与项目更新时间取较晚者。 */
    private LocalDateTime lastActivityOf(Project project) {
        LocalDateTime latest = project.getUpdatedAt();
        ProjectFact fact = factMapper.selectOne(new LambdaQueryWrapper<ProjectFact>()
                .eq(ProjectFact::getProjectId, project.getId())
                .orderByDesc(ProjectFact::getUpdatedAt)
                .last("limit 1"));
        if (fact != null && fact.getUpdatedAt() != null
                && (latest == null || fact.getUpdatedAt().isAfter(latest))) {
            latest = fact.getUpdatedAt();
        }
        return latest;
    }

    // ==================== 规则3：参与人交集 ====================

    private Long matchByParticipants(Meeting meeting) {
        if (meeting.getId() == null) {
            return null;
        }
        List<MeetingSpeaker> speakers = meetingSpeakerMapper.selectList(
                new LambdaQueryWrapper<MeetingSpeaker>().eq(MeetingSpeaker::getMeetingId, meeting.getId()));
        if (speakers == null) {
            speakers = List.of();
        }
        String text = meetingText(meeting);
        Set<Long> participantIds = new LinkedHashSet<>();
        for (MeetingSpeaker speaker : speakers) {
            // 人工映射到系统用户的直接算参与人
            if (speaker.getMappedUserId() != null) {
                participantIds.add(speaker.getMappedUserId());
            }
        }
        for (User user : loadUsers()) {
            if (user.getId() == null || !StringUtils.hasText(user.getRealName())) {
                continue;
            }
            String realName = user.getRealName().trim();
            if (realName.length() < MIN_NAME_LENGTH) {
                continue;
            }
            if (text.contains(realName) || namedIn(speakers, realName)) {
                participantIds.add(user.getId());
            }
        }
        if (participantIds.isEmpty()) {
            return null;
        }
        List<ProjectMember> members = projectMemberMapper.selectList(
                new LambdaQueryWrapper<ProjectMember>().in(ProjectMember::getUserId, participantIds));
        // 每个参与人各自的项目集合；用 Linked* 保持「参与人顺序 / 成员表顺序」的确定性
        Map<Long, Set<Long>> projectsByUser = new LinkedHashMap<>();
        if (members != null) {
            for (ProjectMember member : members) {
                if (member.getUserId() == null || member.getProjectId() == null) {
                    continue;
                }
                projectsByUser
                        .computeIfAbsent(member.getUserId(), key -> new LinkedHashSet<>())
                        .add(member.getProjectId());
            }
        }
        // 真正的交集：逐人 retainAll。查不到成员记录的人直接跳过，不参与交集。
        Set<Long> common = null;
        for (Long participantId : participantIds) {
            Set<Long> owned = projectsByUser.get(participantId);
            if (owned == null || owned.isEmpty()) {
                continue;
            }
            if (common == null) {
                common = new LinkedHashSet<>(owned);
            } else {
                common.retainAll(owned);
            }
            if (common.isEmpty()) {
                return null;
            }
        }
        if (common == null || common.isEmpty()) {
            return null;
        }
        return common.size() == 1 ? common.iterator().next() : null;
    }

    private static boolean namedIn(List<MeetingSpeaker> speakers, String realName) {
        for (MeetingSpeaker speaker : speakers) {
            if (speaker.getDisplayName() != null && speaker.getDisplayName().trim().equals(realName)) {
                return true;
            }
        }
        return false;
    }

    /** 转写文本（原始 + 人工校正）拼起来的检索面。 */
    private static String meetingText(Meeting meeting) {
        StringBuilder text = new StringBuilder();
        if (meeting.getTranscriptJson() != null) {
            text.append(meeting.getTranscriptJson());
        }
        if (meeting.getCorrectedTranscriptJson() != null) {
            text.append('\n').append(meeting.getCorrectedTranscriptJson());
        }
        return text.toString();
    }

    // ==================== 查询 ====================

    private List<Project> loadProjects() {
        List<Project> projects = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .select(Project::getId, Project::getName, Project::getFullPath, Project::getCode,
                        Project::getManagerId, Project::getUpdatedAt));
        return projects == null ? List.of() : projects;
    }

    private List<User> loadUsers() {
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .select(User::getId, User::getRealName));
        return users == null ? List.of() : users;
    }
}
