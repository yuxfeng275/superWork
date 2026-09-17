package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.dto.CreateRequirementConfirmationDTO;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.RequirementConfirmation;
import com.bu.management.mapper.RequirementConfirmationMapper;
import com.bu.management.mapper.RequirementMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequirementConfirmationServiceTest {

    @Mock
    private RequirementConfirmationMapper confirmationMapper;
    @Mock
    private RequirementMapper requirementMapper;
    @Mock
    private YunxiaoHandoffService handoffService;

    @Test
    void confirmationMovesRequirementAndOnlyEnqueuesYunxiaoHandoff() {
        Requirement requirement = new Requirement();
        requirement.setId(8L);
        requirement.setStatus("待确认");

        CreateRequirementConfirmationDTO dto = new CreateRequirementConfirmationDTO();
        dto.setRequirementId(8L);
        dto.setConfirmationType("客户确认");
        dto.setConfirmedBy(999L);

        when(requirementMapper.selectById(8L)).thenReturn(requirement);
        when(confirmationMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        RequirementConfirmationService service = new RequirementConfirmationService(
                confirmationMapper, requirementMapper, handoffService);
        RequirementConfirmation confirmation = service.createConfirmation(dto, 16L);

        assertThat(confirmation.getRequirementId()).isEqualTo(8L);
        assertThat(confirmation.getConfirmedBy()).isEqualTo(16L);
        assertThat(requirement.getStatus()).isEqualTo("开发中");
        verify(requirementMapper).updateById(requirement);
        verify(handoffService).enqueue(8L);
    }
}
