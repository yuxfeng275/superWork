package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bu.management.config.ProjectContextProperties;
import com.bu.management.entity.EmailMessage;
import com.bu.management.entity.Meeting;
import com.bu.management.entity.MeetingSpeaker;
import com.bu.management.entity.Project;
import com.bu.management.entity.ProjectFact;
import com.bu.management.entity.ProjectMember;
import com.bu.management.entity.User;
import com.bu.management.mapper.BuKeyMatterMapper;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.mapper.MeetingMapper;
import com.bu.management.mapper.MeetingSpeakerMapper;
import com.bu.management.mapper.ProjectFactMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.ProjectMemberMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.mapper.YunxiaoWorkitemCacheMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 会议自动归属：三条规则择先命中、不定就返回 null；外部来信默认降级为公司可见。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MeetingProjectAttributionTest {

    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectFactMapper factMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private UserMapper userMapper;
    @Mock private MeetingSpeakerMapper meetingSpeakerMapper;

    @Mock private MeetingMapper meetingMapper;
    @Mock private EmailMessageMapper emailMessageMapper;
    @Mock private YunxiaoWorkitemCacheMapper workitemCacheMapper;
    @Mock private BuKeyMatterMapper keyMatterMapper;
    @Mock private ProjectFactAccessGuard projectFactAccessGuard;

    private MeetingProjectAttributionService attribution;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 的 select(...) 要解列名，纯单元测试里自己把表信息初始化一遍
        for (Class<?> entity : List.of(Project.class, ProjectFact.class, User.class, Meeting.class,
                MeetingSpeaker.class, ProjectMember.class, EmailMessage.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(
                            new org.apache.ibatis.session.Configuration(), ""),
                    entity);
        }
    }

    @BeforeEach
    void setUp() {
        attribution = new MeetingProjectAttributionService(
                projectMapper, factMapper, projectMemberMapper, userMapper, meetingSpeakerMapper);
    }

    @Test
    @DisplayName("规则1：标题命中唯一项目名就归给它，且不再往历史/参与人上找")
    void titleTermWinsFirstRule() {
        Project pms = project(11L, "皇家PMS", "皇家/PMS", "XM01", null);
        when(projectMapper.selectList(any())).thenReturn(List.of(pms));

        Long attributed = attribution.attribute(meeting(1L, 3L, "皇家PMS 十月周会"));

        assertThat(attributed).isEqualTo(11L);
        // 第一规则命中即返回：历史事实与成员表都不该被查
        verifyNoInteractions(factMapper, projectMemberMapper, userMapper, meetingSpeakerMapper);
    }

    @Test
    @DisplayName("规则2：标题认不出时，按负责人名下项目的最近动静取唯一一个")
    void managerHistoryIsSecondRule() {
        Project older = project(21L, "A线", null, null, 7L);
        older.setUpdatedAt(LocalDateTime.of(2026, 3, 1, 10, 0));
        Project recent = project(22L, "B线", null, null, 7L);
        recent.setUpdatedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        when(projectMapper.selectList(any())).thenReturn(List.of(older, recent));
        when(factMapper.selectOne(any())).thenReturn(null);

        Long attributed = attribution.attribute(meeting(2L, 7L, "十月对齐会"));

        assertThat(attributed).isEqualTo(22L);
        // 规则2先于参与人交集命中
        verifyNoInteractions(projectMemberMapper, meetingSpeakerMapper);
    }

    @Test
    @DisplayName("规则3：转写里出现的真名对应的成员，只落在一个项目时才算")
    void participantOverlapIsThirdRule() {
        when(projectMapper.selectList(any())).thenReturn(List.of(
                project(31L, "A线", null, null, null),
                project(32L, "B线", null, null, null)));
        User zhang = new User();
        zhang.setId(5L);
        zhang.setRealName("张伟");
        when(userMapper.selectList(any())).thenReturn(List.of(zhang));
        when(meetingSpeakerMapper.selectList(any())).thenReturn(List.of());
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(member(33L, 5L)));

        Meeting meeting = meeting(3L, 9L, "十月对齐会");
        meeting.setTranscriptJson("{\"segments\":[{\"speaker\":\"SPEAKER_00\",\"text\":\"张伟：先把排期定下来\"}]}");

        assertThat(attribution.attribute(meeting)).isEqualTo(33L);
    }

    @Test
    @DisplayName("规则3：参与人各自的项目取交集——A属于P1+P2、B只属于P1，共同项目P1仍能确定")
    void participantIntersectionFindsSharedProject() {
        when(projectMapper.selectList(any())).thenReturn(List.of(
                project(51L, "A线", null, null, null),
                project(52L, "B线", null, null, null)));
        when(userMapper.selectList(any())).thenReturn(List.of(user(5L, "张伟"), user(6L, "李娜")));
        when(meetingSpeakerMapper.selectList(any())).thenReturn(List.of());
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(
                member(51L, 5L), member(52L, 5L), member(51L, 6L)));

        Meeting meeting = meeting(5L, 9L, "十月对齐会");
        meeting.setTranscriptJson("{\"segments\":[{\"speaker\":\"SPEAKER_00\",\"text\":\"张伟：排期定下来\"},"
                + "{\"speaker\":\"SPEAKER_01\",\"text\":\"李娜：需求先对齐\"}]}");

        assertThat(attribution.attribute(meeting)).isEqualTo(51L);
    }

    @Test
    @DisplayName("规则3：另一个名字查不到成员记录时跳过，不把交集打成空")
    void participantWithoutMembershipIsIgnored() {
        when(projectMapper.selectList(any())).thenReturn(List.of(
                project(51L, "A线", null, null, null),
                project(52L, "B线", null, null, null)));
        when(userMapper.selectList(any())).thenReturn(List.of(
                user(5L, "张伟"), user(6L, "李娜"), user(7L, "王强")));
        when(meetingSpeakerMapper.selectList(any())).thenReturn(List.of());
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(
                member(51L, 5L), member(52L, 5L), member(51L, 6L)));

        Meeting meeting = meeting(6L, 9L, "十月对齐会");
        meeting.setTranscriptJson("{\"segments\":[{\"speaker\":\"SPEAKER_00\",\"text\":\"张伟：排期定下来\"},"
                + "{\"speaker\":\"SPEAKER_01\",\"text\":\"李娜：需求先对齐\"},"
                + "{\"speaker\":\"SPEAKER_02\",\"text\":\"王强：旁听一下\"}]}");

        assertThat(attribution.attribute(meeting)).isEqualTo(51L);
    }

    @Test
    @DisplayName("三条规则都不唯一时返回 null，不猜也不写")
    void returnsNullWhenNothingIsCertain() {
        when(projectMapper.selectList(any())).thenReturn(List.of(
                project(41L, "皇家PMS", null, null, null),
                project(42L, "皇家PMS二期", null, null, null)));
        when(userMapper.selectList(any())).thenReturn(List.of());
        when(meetingSpeakerMapper.selectList(any())).thenReturn(List.of());

        // 标题同时命中两个项目 → 规则1不定；上传人不是任何项目的 PM → 规则2不定；无参与人 → 规则3不定
        assertThat(attribution.attribute(meeting(4L, 9L, "皇家PMS 与 皇家PMS二期 联合评审"))).isNull();
        verifyNoInteractions(projectMemberMapper);
    }

    @Test
    @DisplayName("邮件可见性：发件人域不在公司域名内 → PRIVATE_COMPANY，内部域仍按项目可见")
    void externalSenderEmailDefaultsToCompanyVisibility() {
        ProjectFactService factService = factService("bu-tech.com");
        EmailMessage external = email(88L, "purchasing@client-example.com");
        EmailMessage internal = email(89L, "li.ming@bu-tech.com");
        when(meetingMapper.selectList(any())).thenReturn(List.of());
        when(emailMessageMapper.selectList(any())).thenReturn(List.of(external, internal));
        when(workitemCacheMapper.selectList(any())).thenReturn(List.of());
        when(keyMatterMapper.selectList(any())).thenReturn(List.of());
        when(factMapper.selectOne(any())).thenReturn(null);
        when(factMapper.insert(any(ProjectFact.class))).thenReturn(1);

        factService.ingestProject(11L, LocalDateTime.now().minusDays(1));

        ArgumentCaptor<ProjectFact> captor = ArgumentCaptor.forClass(ProjectFact.class);
        verify(factMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ProjectFact::getSourceRef, ProjectFact::getVisibility)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("email_message:88", ProjectFactService.VISIBILITY_COMPANY),
                        org.assertj.core.groups.Tuple.tuple("email_message:89", ProjectFactService.VISIBILITY_PROJECT));
        assertThat(ProjectFactService.VISIBILITY_COMPANY).isEqualTo("PRIVATE_COMPANY");
    }

    // ==================== 夹具 ====================

    private ProjectFactService factService(String companyDomains) {
        ProjectContextProperties properties = new ProjectContextProperties();
        properties.setMaxSourceRows(50);
        ProjectFactService service = new ProjectFactService(factMapper, projectMapper, meetingMapper,
                emailMessageMapper, workitemCacheMapper, keyMatterMapper, properties,
                new ProjectFactPolicy(projectFactAccessGuard), new FactRedactionService(), attribution);
        ReflectionTestUtils.setField(service, "companyDomains", companyDomains);
        return service;
    }

    private static Project project(Long id, String name, String fullPath, String code, Long managerId) {
        Project project = new Project();
        project.setId(id);
        project.setName(name);
        project.setFullPath(fullPath);
        project.setCode(code);
        project.setManagerId(managerId);
        project.setStatus(1);
        return project;
    }

    private static Meeting meeting(Long id, Long ownerUserId, String title) {
        Meeting meeting = new Meeting();
        meeting.setId(id);
        meeting.setOwnerUserId(ownerUserId);
        meeting.setTitle(title);
        meeting.setMeetingDate(LocalDateTime.now().toLocalDate());
        meeting.setUpdatedAt(LocalDateTime.now());
        return meeting;
    }

    private static User user(Long id, String realName) {
        User user = new User();
        user.setId(id);
        user.setRealName(realName);
        return user;
    }

    private static ProjectMember member(Long projectId, Long userId) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        return member;
    }

    private static EmailMessage email(Long id, String senderAddress) {
        EmailMessage mail = new EmailMessage();
        mail.setId(id);
        mail.setProjectId(11L);
        mail.setOwnerUserId(3L);
        mail.setSubject("十月询价");
        mail.setBodyText("请报一下设备价格");
        mail.setSenderAddress(senderAddress);
        mail.setReceivedAt(LocalDateTime.now());
        return mail;
    }
}
