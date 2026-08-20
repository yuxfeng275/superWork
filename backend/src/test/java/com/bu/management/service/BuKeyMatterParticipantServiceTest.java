package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.dto.BuKeyMatterRequest;
import com.bu.management.entity.BuKeyMatter;
import com.bu.management.entity.BuKeyMatterParticipant;
import com.bu.management.entity.Project;
import com.bu.management.entity.User;
import com.bu.management.mapper.BuKeyMatterMapper;
import com.bu.management.mapper.BuKeyMatterParticipantMapper;
import com.bu.management.mapper.BuKeyMatterWeeklyUpdateMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuKeyMatterParticipantServiceTest {

    @Mock private BuKeyMatterMapper matterMapper;
    @Mock private BuKeyMatterWeeklyUpdateMapper weeklyUpdateMapper;
    @Mock private UserMapper userMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private BuKeyMatterParticipantMapper participantMapper;
    @Mock private BuKeyMatterAccessService accessService;

    private BuKeyMatterService service;

    @BeforeEach
    void setUp() {
        service = new BuKeyMatterService(
                matterMapper, weeklyUpdateMapper, userMapper, projectMapper,
                participantMapper, accessService);
    }

    @Test
    void createDeduplicatesParticipantsAndAlwaysAddsOwner() {
        when(userMapper.selectById(7L)).thenReturn(activeUser(7L, "石家乐"));
        when(projectMapper.selectById(3L)).thenReturn(project(3L, "皇家项目"));
        when(userMapper.selectBatchIds(List.of(16L))).thenReturn(List.of(activeUser(16L, "于峰")));
        when(matterMapper.insert(any(BuKeyMatter.class))).thenAnswer(invocation -> {
            invocation.<BuKeyMatter>getArgument(0).setId(11L);
            return 1;
        });
        BuKeyMatterRequest request = request();
        request.setParticipantIds(List.of(7L, 16L, 16L));

        service.create(request, 16L);

        ArgumentCaptor<BuKeyMatterParticipant> captor =
                ArgumentCaptor.forClass(BuKeyMatterParticipant.class);
        verify(participantMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(BuKeyMatterParticipant::getKeyMatterId)
                .containsExactly(11L, 11L);
        assertThat(captor.getAllValues())
                .extracting(BuKeyMatterParticipant::getUserId)
                .containsExactly(7L, 16L);
    }

    @Test
    void createWithNullParticipantIdsDefaultsToOwnerOnly() {
        when(userMapper.selectById(7L)).thenReturn(activeUser(7L, "石家乐"));
        when(projectMapper.selectById(3L)).thenReturn(project(3L, "皇家项目"));
        when(matterMapper.insert(any(BuKeyMatter.class))).thenAnswer(invocation -> {
            invocation.<BuKeyMatter>getArgument(0).setId(11L);
            return 1;
        });

        service.create(request(), 16L);

        ArgumentCaptor<BuKeyMatterParticipant> captor =
                ArgumentCaptor.forClass(BuKeyMatterParticipant.class);
        verify(participantMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
    }

    @Test
    void updateRemovesOldParticipantsAndAddsNewOwner() {
        BuKeyMatter matter = matter(11L, 7L);
        when(matterMapper.selectByIdForUpdate(11L)).thenReturn(matter);
        when(userMapper.selectById(21L)).thenReturn(activeUser(21L, "新负责人"));
        when(projectMapper.selectById(3L)).thenReturn(project(3L, "皇家项目"));
        BuKeyMatterRequest request = request();
        request.setOwnerId(21L);
        request.setParticipantIds(List.of(21L));

        service.update(11L, request);

        assertThat(matter.getOwnerId()).isEqualTo(21L);
        verify(participantMapper).delete(any(Wrapper.class));
        ArgumentCaptor<BuKeyMatterParticipant> captor =
                ArgumentCaptor.forClass(BuKeyMatterParticipant.class);
        verify(participantMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(21L);
    }

    @Test
    void updateKeepsOldOwnerOnlyWhenStillRequested() {
        BuKeyMatter matter = matter(11L, 7L);
        when(matterMapper.selectByIdForUpdate(11L)).thenReturn(matter);
        when(userMapper.selectById(7L)).thenReturn(activeUser(7L, "石家乐"));
        when(projectMapper.selectById(3L)).thenReturn(project(3L, "皇家项目"));
        when(userMapper.selectBatchIds(List.of(16L))).thenReturn(List.of(activeUser(16L, "于峰")));
        BuKeyMatterRequest request = request();
        request.setParticipantIds(List.of(7L, 16L));

        service.update(11L, request);

        verify(participantMapper).delete(any(Wrapper.class));
        ArgumentCaptor<BuKeyMatterParticipant> captor =
                ArgumentCaptor.forClass(BuKeyMatterParticipant.class);
        verify(participantMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(BuKeyMatterParticipant::getUserId)
                .containsExactly(7L, 16L);
    }

    @Test
    void missingParticipantIsRejected() {
        when(userMapper.selectById(7L)).thenReturn(activeUser(7L, "石家乐"));
        when(projectMapper.selectById(3L)).thenReturn(project(3L, "皇家项目"));
        when(userMapper.selectBatchIds(List.of(20L))).thenReturn(List.of());
        BuKeyMatterRequest request = request();
        request.setParticipantIds(List.of(7L, 20L));

        assertThatThrownBy(() -> service.create(request, 16L))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("参与人不存在或已停用");
        verify(matterMapper, never()).insert(any(BuKeyMatter.class));
    }

    @Test
    void disabledParticipantIsRejected() {
        when(userMapper.selectById(7L)).thenReturn(activeUser(7L, "石家乐"));
        when(projectMapper.selectById(3L)).thenReturn(project(3L, "皇家项目"));
        User disabled = new User();
        disabled.setId(20L);
        disabled.setRealName("停用用户");
        disabled.setStatus(0);
        when(userMapper.selectBatchIds(List.of(20L))).thenReturn(List.of(disabled));
        BuKeyMatterRequest request = request();
        request.setParticipantIds(List.of(7L, 20L));

        assertThatThrownBy(() -> service.create(request, 16L))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("参与人不存在或已停用");
        verify(matterMapper, never()).insert(any(BuKeyMatter.class));
    }

    private BuKeyMatterRequest request() {
        BuKeyMatterRequest request = new BuKeyMatterRequest();
        request.setTitle("皇家会员体系二期上线");
        request.setDescription("完成核心会员链路切换");
        request.setProjectId(3L);
        request.setOwnerId(7L);
        request.setStartDate(LocalDate.of(2026, 8, 3));
        request.setPlannedCompletionDate(LocalDate.of(2026, 8, 28));
        return request;
    }

    private BuKeyMatter matter(Long id, Long ownerId) {
        BuKeyMatter matter = new BuKeyMatter();
        matter.setId(id);
        matter.setTitle("事项" + id);
        matter.setOwnerId(ownerId);
        matter.setProjectId(3L);
        matter.setPriority("P1");
        matter.setStatus("推进中");
        matter.setProgress(40);
        matter.setStartDate(LocalDate.of(2026, 8, 1));
        matter.setPlannedCompletionDate(LocalDate.of(2026, 8, 28));
        matter.setSortOrder(0);
        matter.setCreatedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
        matter.setUpdatedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
        return matter;
    }

    private User activeUser(Long id, String name) {
        User user = new User();
        user.setId(id);
        user.setRealName(name);
        user.setStatus(1);
        return user;
    }

    private Project project(Long id, String name) {
        Project project = new Project();
        project.setId(id);
        project.setName(name);
        return project;
    }
}
