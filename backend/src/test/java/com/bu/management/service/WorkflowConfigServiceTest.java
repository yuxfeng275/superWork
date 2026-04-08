package com.bu.management.service;

import com.bu.management.dto.WorkflowConfigRequest;
import com.bu.management.entity.WorkflowConfig;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.WorkflowConfigMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowConfigService 测试")
class WorkflowConfigServiceTest {

    @Mock
    private WorkflowConfigMapper workflowConfigMapper;

    @InjectMocks
    private WorkflowConfigService workflowConfigService;

    @Nested
    @DisplayName("create 方法测试")
    class CreateTests {

        @Test
        @DisplayName("创建工作流配置时兼容旧值并序列化角色")
        void create_normalizesLegacyValues_success() {
            WorkflowConfigRequest request = new WorkflowConfigRequest();
            request.setRequirementType("PROJECT");
            request.setFromStatus("待评估");
            request.setToStatus("评估中");
            request.setAllowedRoles(java.util.List.of("PM", "TECH_MANAGER"));
            request.setConditionType("NONE");
            request.setIsActive(1);
            request.setSortOrder(3);

            when(workflowConfigMapper.insert(any(WorkflowConfig.class))).thenReturn(1);

            WorkflowConfig result = workflowConfigService.create(request);

            assertThat(result.getRequirementType()).isEqualTo("项目需求");
            assertThat(result.getAllowedRoles()).isEqualTo("[\"项目经理\",\"技术经理\"]");
            assertThat(result.getConditionType()).isNull();

            ArgumentCaptor<WorkflowConfig> captor = ArgumentCaptor.forClass(WorkflowConfig.class);
            verify(workflowConfigMapper).insert(captor.capture());
            assertThat(captor.getValue().getSortOrder()).isEqualTo(3);
        }

        @Test
        @DisplayName("当前状态和目标状态相同时抛出异常")
        void create_sameStatus_throwsException() {
            WorkflowConfigRequest request = new WorkflowConfigRequest();
            request.setRequirementType("项目需求");
            request.setFromStatus("待评估");
            request.setToStatus("待评估");
            request.setAllowedRoles(java.util.List.of("项目经理"));
            request.setConditionType("");
            request.setIsActive(1);
            request.setSortOrder(1);

            assertThatThrownBy(() -> workflowConfigService.create(request))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("当前状态和目标状态不能相同");
        }

        @Test
        @DisplayName("状态与需求类型不匹配时抛出异常")
        void create_invalidStatusForType_throwsException() {
            WorkflowConfigRequest request = new WorkflowConfigRequest();
            request.setRequirementType("产品需求");
            request.setFromStatus("已交付");
            request.setToStatus("已验收");
            request.setAllowedRoles(java.util.List.of("产品经理"));
            request.setConditionType("");
            request.setIsActive(1);
            request.setSortOrder(1);

            assertThatThrownBy(() -> workflowConfigService.create(request))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("当前状态不属于该需求类型");
        }
    }

    @Nested
    @DisplayName("update/delete 方法测试")
    class UpdateDeleteTests {

        @Test
        @DisplayName("更新不存在的工作流配置时返回404")
        void update_missingConfig_throwsNotFound() {
            WorkflowConfigRequest request = new WorkflowConfigRequest();
            request.setRequirementType("项目需求");
            request.setFromStatus("待评估");
            request.setToStatus("评估中");
            request.setAllowedRoles(java.util.List.of("项目经理"));
            request.setConditionType("");
            request.setIsActive(1);
            request.setSortOrder(1);

            when(workflowConfigMapper.selectById(99L)).thenReturn(null);

            assertThatThrownBy(() -> workflowConfigService.update(99L, request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("工作流配置不存在");
        }

        @Test
        @DisplayName("删除工作流配置成功")
        void delete_existingConfig_success() {
            WorkflowConfig existingConfig = new WorkflowConfig();
            existingConfig.setId(1L);

            when(workflowConfigMapper.selectById(1L)).thenReturn(existingConfig);

            workflowConfigService.delete(1L);

            verify(workflowConfigMapper).deleteById(1L);
        }
    }
}
