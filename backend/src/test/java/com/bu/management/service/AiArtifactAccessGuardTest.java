package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.controller.AiArtifactController;
import com.bu.management.entity.AiArtifact;
import com.bu.management.mapper.AiArtifactMapper;
import com.bu.management.vo.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

/**
 * 制品按项目准入：非成员不能裁决 / 采纳，待我评审按准入过滤，项目负责人采纳放行。
 *
 * <p>准入判定本身由 {@link ProjectFactAccessGuard} 负责，这里全部 mock，只验挂载点。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiArtifactAccessGuardTest {

    private static final long REVIEWER = 3L;
    private static final long OWNER = 9L;
    private static final long PROJECT = 11L;
    private static final long FOREIGN_PROJECT = 12L;

    @Mock private AiArtifactMapper artifactMapper;
    @Mock private ProjectFactService projectFactService;
    @Mock private ProjectFactAccessGuard accessGuard;

    private AiArtifactService service;
    private AiArtifactController controller;

    @BeforeEach
    void setUp() {
        service = new AiArtifactService(artifactMapper, projectFactService, accessGuard,
                new ObjectMapper());
        controller = new AiArtifactController(service, accessGuard);
        when(artifactMapper.updateById(any(AiArtifact.class))).thenReturn(1);
    }

    @Test
    @DisplayName("非成员裁决：403，且不落库")
    void nonMemberCannotDecide() {
        AiArtifact inReview = artifact(5L, PROJECT, AiArtifactService.STATUS_IN_REVIEW, OWNER);
        when(artifactMapper.selectById(5L)).thenReturn(inReview);
        when(accessGuard.canRead(REVIEWER, PROJECT)).thenReturn(false);

        ResponseEntity<?> response = controller.decide(REVIEWER, 5L,
                new AiArtifactController.DecideRequest(true, null));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(((Result<?>) response.getBody()).getCode()).isEqualTo(403);
        assertThat(((Result<?>) response.getBody()).getMessage()).isEqualTo("无权访问该项目的制品");
        assertThat(inReview.getStatus()).isEqualTo(AiArtifactService.STATUS_IN_REVIEW);
        verify(artifactMapper, never()).updateById(any(AiArtifact.class));
    }

    @Test
    @DisplayName("非成员采纳：403，不回写项目事实")
    void nonMemberCannotApply() {
        AiArtifact approved = artifact(8L, PROJECT, AiArtifactService.STATUS_APPROVED, OWNER);
        when(artifactMapper.selectById(8L)).thenReturn(approved);
        when(accessGuard.canWrite(REVIEWER, PROJECT)).thenReturn(false);

        ResponseEntity<?> response = controller.apply(REVIEWER, 8L);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(((Result<?>) response.getBody()).getMessage()).isEqualTo("无权采纳该项目的制品");
        assertThat(approved.getStatus()).isEqualTo(AiArtifactService.STATUS_APPROVED);
        verify(projectFactService, never())
                .recordArtifactFact(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("服务层防线：无写权限时 apply 直接拒绝，不落库也不回写事实")
    void applyRejectedByServiceGuard() {
        AiArtifact approved = artifact(8L, PROJECT, AiArtifactService.STATUS_APPROVED, OWNER);
        when(artifactMapper.selectById(8L)).thenReturn(approved);
        when(accessGuard.canWrite(REVIEWER, PROJECT)).thenReturn(false);

        assertThatThrownBy(() -> service.apply(REVIEWER, 8L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("无权采纳该项目的制品");

        assertThat(approved.getStatus()).isEqualTo(AiArtifactService.STATUS_APPROVED);
        verify(artifactMapper, never()).updateById(any(AiArtifact.class));
        verify(projectFactService, never())
                .recordArtifactFact(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("待我评审：其他项目的制品被过滤掉，只返回有准入的")
    void myReviewsFiltersForeignProjects() {
        AiArtifact mine = artifact(5L, PROJECT, AiArtifactService.STATUS_IN_REVIEW, OWNER);
        AiArtifact foreign = artifact(6L, FOREIGN_PROJECT, AiArtifactService.STATUS_IN_REVIEW, OWNER);
        when(artifactMapper.selectList(any())).thenReturn(List.of(mine, foreign));
        when(accessGuard.canRead(REVIEWER, PROJECT)).thenReturn(true);
        when(accessGuard.canRead(REVIEWER, FOREIGN_PROJECT)).thenReturn(false);

        ResponseEntity<?> response = controller.myReviews(REVIEWER, "IN_REVIEW");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<AiArtifact> data = (List<AiArtifact>) ((Result<?>) response.getBody()).getData();
        assertThat(data).extracting(AiArtifact::getProjectId).containsExactly(PROJECT);
    }

    @Test
    @DisplayName("项目负责人采纳：通过并回写一条项目事实")
    void projectManagerApplyPasses() {
        AiArtifact approved = artifact(8L, PROJECT, AiArtifactService.STATUS_APPROVED, OWNER);
        when(artifactMapper.selectById(8L)).thenReturn(approved);
        when(accessGuard.canWrite(2L, PROJECT)).thenReturn(true);

        ResponseEntity<?> response = controller.apply(2L, 8L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(approved.getStatus()).isEqualTo(AiArtifactService.STATUS_APPLIED);
        verify(projectFactService).recordArtifactFact(
                eq(PROJECT), eq(8L), eq("皇家项目排期方案"), eq("方案正文"));
    }

    @Test
    @DisplayName("列表：不带 projectId 返回 400，无准入返回 403")
    void listRequiresProjectAndAccess() {
        ResponseEntity<?> missing = controller.list(REVIEWER, null, null);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);

        when(accessGuard.canRead(REVIEWER, PROJECT)).thenReturn(false);
        ResponseEntity<?> forbidden = controller.list(REVIEWER, PROJECT, null);
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(((Result<?>) forbidden.getBody()).getMessage()).isEqualTo("无权访问该项目的制品");
    }

    private AiArtifact artifact(Long id, Long projectId, String status, Long ownerUserId) {
        AiArtifact artifact = new AiArtifact();
        artifact.setId(id);
        artifact.setProjectId(projectId);
        artifact.setOwnerUserId(ownerUserId);
        artifact.setKind(AiArtifactService.KIND_PLAN);
        artifact.setTitle("皇家项目排期方案");
        artifact.setContent("方案正文");
        artifact.setEvidence("[12,15]");
        artifact.setStatus(status);
        return artifact;
    }
}
