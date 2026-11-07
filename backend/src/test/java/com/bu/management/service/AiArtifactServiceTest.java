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

import com.bu.management.entity.AiArtifact;
import com.bu.management.mapper.AiArtifactMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 制品与评审队列：类型白名单、无证据不入队、状态单向流转、驳回必须带原因、采纳才回流。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiArtifactServiceTest {

    private static final String EVIDENCE = "[12,15]";

    @Mock private AiArtifactMapper artifactMapper;
    @Mock private ProjectFactService projectFactService;

    private AiArtifactService service;

    @BeforeEach
    void setUp() {
        service = new AiArtifactService(artifactMapper, projectFactService, new ObjectMapper());
        when(artifactMapper.insert(any(AiArtifact.class))).thenReturn(1);
        when(artifactMapper.updateById(any(AiArtifact.class))).thenReturn(1);
    }

    @Test
    @DisplayName("类型不在白名单：拒绝，不落库")
    void createRejectsUnknownKind() {
        assertThatThrownBy(() -> service.create(9L, 11L, request("PLAN_X", EVIDENCE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的制品类型");
        verify(artifactMapper, never()).insert(any(AiArtifact.class));
    }

    @Test
    @DisplayName("无证据（空/空数组/非 JSON）：拒绝进评审队列")
    void createRequiresEvidence() {
        for (String evidence : new String[] {null, "", "   ", "[]", "not-json", "{\"id\":1}"}) {
            assertThatThrownBy(() -> service.create(9L, 11L, request("PLAN", evidence)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("制品必须挂证据（project_fact.id）");
        }
        verify(artifactMapper, never()).insert(any(AiArtifact.class));
    }

    @Test
    @DisplayName("合法创建：DRAFT + 产出者本人 + 证据原样保存")
    void createPersistsDraft() {
        AiArtifact created = service.create(9L, 11L, request("plan", EVIDENCE));

        ArgumentCaptor<AiArtifact> captor = ArgumentCaptor.forClass(AiArtifact.class);
        verify(artifactMapper).insert(captor.capture());
        AiArtifact saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(AiArtifactService.STATUS_DRAFT);
        assertThat(saved.getOwnerUserId()).isEqualTo(9L);
        assertThat(saved.getProjectId()).isEqualTo(11L);
        // 小写归一成白名单值
        assertThat(saved.getKind()).isEqualTo(AiArtifactService.KIND_PLAN);
        assertThat(saved.getEvidence()).isEqualTo(EVIDENCE);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("submit：DRAFT → IN_REVIEW，重复提交或非本人提交被拒")
    void submitMovesDraftToInReview() {
        AiArtifact draft = artifact(AiArtifactService.STATUS_DRAFT, 9L);
        when(artifactMapper.selectById(5L)).thenReturn(draft);

        AiArtifact submitted = service.submit(9L, 5L);
        assertThat(submitted.getStatus()).isEqualTo(AiArtifactService.STATUS_IN_REVIEW);

        // 已在评审中，不能再提交
        assertThatThrownBy(() -> service.submit(9L, 5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许提交评审");

        // 非产出人不能提交
        AiArtifact others = artifact(AiArtifactService.STATUS_DRAFT, 9L);
        when(artifactMapper.selectById(6L)).thenReturn(others);
        assertThatThrownBy(() -> service.submit(10L, 6L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只有产出人可以提交评审");

        // 驳回后可改后重提
        AiArtifact rejected = artifact(AiArtifactService.STATUS_REJECTED, 9L);
        when(artifactMapper.selectById(7L)).thenReturn(rejected);
        assertThat(service.submit(9L, 7L).getStatus()).isEqualTo(AiArtifactService.STATUS_IN_REVIEW);
    }

    @Test
    @DisplayName("decide：驳回必须带原因，通过写 reviewerId/decidedAt，非评审中不可裁决")
    void decideRequiresReasonOnReject() {
        AiArtifact inReview = artifact(AiArtifactService.STATUS_IN_REVIEW, 9L);
        when(artifactMapper.selectById(5L)).thenReturn(inReview);

        assertThatThrownBy(() -> service.decide(3L, 5L, false, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("驳回必须写明原因");
        assertThat(inReview.getStatus()).isEqualTo(AiArtifactService.STATUS_IN_REVIEW);

        AiArtifact approved = service.decide(3L, 5L, true, null);
        assertThat(approved.getStatus()).isEqualTo(AiArtifactService.STATUS_APPROVED);
        assertThat(approved.getReviewerId()).isEqualTo(3L);
        assertThat(approved.getDecidedAt()).isNotNull();

        // 已经 APPROVED，不能再裁决第二次
        assertThatThrownBy(() -> service.decide(3L, 5L, false, "不合要求"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只有评审中的制品可以裁决");
    }

    @Test
    @DisplayName("decide 驳回：状态 REJECTED 且原因落库")
    void decideRejectStoresReason() {
        AiArtifact inReview = artifact(AiArtifactService.STATUS_IN_REVIEW, 9L);
        when(artifactMapper.selectById(5L)).thenReturn(inReview);

        AiArtifact rejected = service.decide(3L, 5L, false, "  依据的事实不全  ");

        assertThat(rejected.getStatus()).isEqualTo(AiArtifactService.STATUS_REJECTED);
        assertThat(rejected.getRejectReason()).isEqualTo("依据的事实不全");
        assertThat(rejected.getReviewerId()).isEqualTo(3L);
        verify(artifactMapper).updateById(rejected);
    }

    @Test
    @DisplayName("apply：只有 APPROVED 可采纳，采纳后回写一条项目事实")
    void applyOnlyFromApproved() {
        AiArtifact draft = artifact(AiArtifactService.STATUS_DRAFT, 9L);
        when(artifactMapper.selectById(5L)).thenReturn(draft);
        assertThatThrownBy(() -> service.apply(9L, 5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只有已通过的制品可以采纳");

        AiArtifact approved = artifact(AiArtifactService.STATUS_APPROVED, 9L);
        approved.setId(8L);
        when(artifactMapper.selectById(8L)).thenReturn(approved);

        AiArtifact applied = service.apply(2L, 8L);

        assertThat(applied.getStatus()).isEqualTo(AiArtifactService.STATUS_APPLIED);
        verify(projectFactService).recordArtifactFact(
                eq(11L), eq(8L), eq("皇家项目排期方案"), eq("方案正文"));
        // DRAFT 那次不该触发回流
        verify(projectFactService, never()).recordArtifactFact(anyLong(), eq(5L), anyString(), anyString());
    }

    private AiArtifactService.ArtifactCreateRequest request(String kind, String evidence) {
        return new AiArtifactService.ArtifactCreateRequest(
                "皇家项目排期方案", kind, "方案正文", evidence, null, null, 11L, "T-1");
    }

    private AiArtifact artifact(String status, Long ownerUserId) {
        AiArtifact artifact = new AiArtifact();
        artifact.setId(5L);
        artifact.setProjectId(11L);
        artifact.setOwnerUserId(ownerUserId);
        artifact.setKind(AiArtifactService.KIND_PLAN);
        artifact.setTitle("皇家项目排期方案");
        artifact.setContent("方案正文");
        artifact.setEvidence(EVIDENCE);
        artifact.setStatus(status);
        return artifact;
    }
}
