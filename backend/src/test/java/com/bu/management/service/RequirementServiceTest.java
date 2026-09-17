package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.RequirementRequest;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.CustomerContact;
import com.bu.management.entity.DesignWorkLog;
import com.bu.management.entity.Project;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.Task;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.CustomerContactMapper;
import com.bu.management.mapper.DesignWorkLogMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RequirementMapper;
import com.bu.management.mapper.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RequirementService 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RequirementService 测试")
class RequirementServiceTest {

    @Mock
    private RequirementMapper requirementMapper;

    @Mock
    private BusinessLineMapper businessLineMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private CustomerContactMapper customerContactMapper;

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private DesignWorkLogMapper designWorkLogMapper;

    @InjectMocks
    private RequirementService requirementService;

    private RequirementRequest validRequest;
    private BusinessLine existingBusinessLine;
    private Project existingProject;
    private CustomerContact existingContact;
    private Requirement existingRequirement;

    @BeforeEach
    void setUp() {
        // 有效的需求请求
        validRequest = new RequirementRequest();
        validRequest.setTitle("新需求");
        validRequest.setDescription("需求描述");
        validRequest.setType("产品需求");
        validRequest.setBusinessLineId(1L);
        validRequest.setProjectId(1L);
        validRequest.setPriority("高");
        validRequest.setSource("客户反馈");
        validRequest.setExpectedOnlineDate(LocalDate.now().plusDays(30));

        // 已存在的业务线
        existingBusinessLine = new BusinessLine();
        existingBusinessLine.setId(1L);
        existingBusinessLine.setName("电商业务线");
        existingBusinessLine.setStatus(1);

        // 已存在的项目
        existingProject = new Project();
        existingProject.setId(1L);
        existingProject.setName("ERP系统");
        existingProject.setBusinessLineId(1L);
        existingProject.setStatus(1);

        // 已存在的客户联系人
        existingContact = new CustomerContact();
        existingContact.setId(1L);
        existingContact.setName("张三");
        existingContact.setProjectId(1L);

        // 已存在的需求
        existingRequirement = new Requirement();
        existingRequirement.setId(1L);
        existingRequirement.setReqNo("REQ202604030001");
        existingRequirement.setTitle("已存在的需求");
        existingRequirement.setDescription("描述");
        existingRequirement.setType("产品需求");
        existingRequirement.setBusinessLineId(1L);
        existingRequirement.setStatus("待评估");
        existingRequirement.setPriority("中");
        existingRequirement.setCreatorId(1L);
        existingRequirement.setCreatedAt(LocalDateTime.now());
    }

    @Nested
    @DisplayName("create 方法测试")
    class CreateTests {

        @Test
        @DisplayName("创建产品需求成功")
        void create_productRequirement_success() {
            // given
            validRequest.setType("产品需求");
            validRequest.setProjectId(null); // 产品需求不需要项目

            when(businessLineMapper.selectById(1L)).thenReturn(existingBusinessLine);
            when(requirementMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            when(requirementMapper.insert(any(Requirement.class))).thenReturn(1);

            // when
            Requirement result = requirementService.create(validRequest, 1L);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getReqNo()).startsWith("REQ");
            assertThat(result.getStatus()).isEqualTo("待评估");

            ArgumentCaptor<Requirement> captor = ArgumentCaptor.forClass(Requirement.class);
            verify(requirementMapper).insert(captor.capture());
            assertThat(captor.getValue().getTitle()).isEqualTo("新需求");
            assertThat(captor.getValue().getCreatorId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("创建项目需求成功")
        void create_projectRequirement_success() {
            // given
            validRequest.setType("项目需求");
            validRequest.setCustomerContactId(1L); // 项目需求需要客户联系人ID

            when(businessLineMapper.selectById(1L)).thenReturn(existingBusinessLine);
            when(projectMapper.selectById(1L)).thenReturn(existingProject);
            when(customerContactMapper.selectById(1L)).thenReturn(existingContact);
            when(requirementMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            when(requirementMapper.insert(any(Requirement.class))).thenReturn(1);

            // when
            Requirement result = requirementService.create(validRequest, 1L);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getType()).isEqualTo("项目需求");
        }

        @Test
        @DisplayName("业务线不存在时抛出异常")
        void create_businessLineNotFound_throwsException() {
            // given
            when(businessLineMapper.selectById(1L)).thenReturn(null);

            // when & then
            assertThatThrownBy(() -> requirementService.create(validRequest, 1L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("业务线不存在");
        }

        @Test
        @DisplayName("项目不存在时抛出异常")
        void create_projectNotFound_throwsException() {
            // given
            when(businessLineMapper.selectById(1L)).thenReturn(existingBusinessLine);
            when(projectMapper.selectById(1L)).thenReturn(null);

            // when & then
            assertThatThrownBy(() -> requirementService.create(validRequest, 1L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("项目不存在");
        }

        @Test
        @DisplayName("项目需求未提供客户联系人时抛出异常")
        void create_projectRequirementWithoutContact_throwsException() {
            // given
            validRequest.setType("项目需求");
            validRequest.setCustomerContactId(null);

            when(businessLineMapper.selectById(1L)).thenReturn(existingBusinessLine);
            when(projectMapper.selectById(1L)).thenReturn(existingProject);

            // when & then
            assertThatThrownBy(() -> requirementService.create(validRequest, 1L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("项目需求必须提供客户联系人");
        }
    }

    @Nested
    @DisplayName("update 方法测试")
    class UpdateTests {

        @Test
        @DisplayName("更新需求成功")
        void update_validRequest_success() {
            // given
            when(requirementMapper.selectById(1L)).thenReturn(existingRequirement);
            when(businessLineMapper.selectById(1L)).thenReturn(existingBusinessLine);

            RequirementRequest updateRequest = new RequirementRequest();
            updateRequest.setTitle("更新后的标题");
            updateRequest.setDescription("更新后的描述");
            updateRequest.setType("产品需求");
            updateRequest.setBusinessLineId(1L);
            updateRequest.setPriority("高");

            // when
            Requirement result = requirementService.update(1L, updateRequest);

            // then
            assertThat(result).isNotNull();
            verify(requirementMapper).updateById(any(Requirement.class));
        }

        @Test
        @DisplayName("更新不存在的需求时抛出异常")
        void update_requirementNotFound_throwsException() {
            // given
            when(requirementMapper.selectById(99L)).thenReturn(null);

            // when & then
            assertThatThrownBy(() -> requirementService.update(99L, validRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("需求不存在");
        }
    }

    @Nested
    @DisplayName("delete 方法测试")
    class DeleteTests {

        @Test
        @DisplayName("删除待评估状态的需求成功")
        void delete_pendingRequirement_success() {
            // given
            when(requirementMapper.selectById(1L)).thenReturn(existingRequirement);

            // when
            requirementService.delete(1L);

            // then
            verify(requirementMapper).deleteById(1L);
        }

        @Test
        @DisplayName("删除不存在的需求时抛出异常")
        void delete_requirementNotFound_throwsException() {
            // given
            when(requirementMapper.selectById(99L)).thenReturn(null);

            // when & then
            assertThatThrownBy(() -> requirementService.delete(99L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("需求不存在");
        }

        @Test
        @DisplayName("删除非待评估状态的需求时抛出异常")
        void delete_nonPendingRequirement_throwsException() {
            // given
            existingRequirement.setStatus("评估中");
            when(requirementMapper.selectById(1L)).thenReturn(existingRequirement);

            // when & then
            assertThatThrownBy(() -> requirementService.delete(1L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("只有待评估状态的需求才能删除");
        }
    }

    @Nested
    @DisplayName("getById 方法测试")
    class GetByIdTests {

        @Test
        @DisplayName("获取需求详情成功")
        void getById_existingRequirement_returnsRequirement() {
            // given
            when(requirementMapper.selectById(1L)).thenReturn(existingRequirement);

            // when
            Requirement result = requirementService.getById(1L);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getReqNo()).isEqualTo("REQ202604030001");
        }

        @Test
        @DisplayName("获取不存在的需求时抛出异常")
        void getById_requirementNotFound_throwsException() {
            // given
            when(requirementMapper.selectById(99L)).thenReturn(null);

            // when & then
            assertThatThrownBy(() -> requirementService.getById(99L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("需求不存在");
        }
    }

    @Nested
    @DisplayName("executeStageAction 方法测试")
    class ExecuteStageActionTests {

        @Test
        @DisplayName("开始评估成功")
        void executeStageAction_startEval_success() {
            when(requirementMapper.selectById(1L)).thenReturn(existingRequirement);

            Requirement result = requirementService.executeStageAction(1L, "start_eval");

            assertThat(result.getStatus()).isEqualTo("评估中");
            verify(requirementMapper).updateById(existingRequirement);
        }

        @Test
        @DisplayName("驳回需求成功")
        void executeStageAction_reject_success() {
            existingRequirement.setStatus("评估中");
            when(requirementMapper.selectById(1L)).thenReturn(existingRequirement);

            Requirement result = requirementService.executeStageAction(1L, "reject");

            assertThat(result.getStatus()).isEqualTo("已拒绝");
            verify(requirementMapper).updateById(existingRequirement);
        }

        @Test
        @DisplayName("待设计且已配置设计规划时可以开始设计")
        void executeStageAction_startDesign_success() {
            DesignWorkLog log = new DesignWorkLog();
            log.setId(10L);
            log.setRequirementId(1L);
            log.setWorkType("UI设计");
            log.setStatus("待开始");

            existingRequirement.setStatus("待设计");
            when(requirementMapper.selectById(1L)).thenReturn(existingRequirement);
            when(designWorkLogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of(log));

            Requirement result = requirementService.executeStageAction(1L, "start_design");

            assertThat(result.getStatus()).isEqualTo("设计中");
            verify(designWorkLogMapper).updateById(any(DesignWorkLog.class));
            verify(requirementMapper).updateById(existingRequirement);
        }

        @Test
        @DisplayName("待设计但未配置设计规划时不能开始设计")
        void executeStageAction_startDesign_withoutPlan_throwsException() {
            existingRequirement.setStatus("待设计");
            when(requirementMapper.selectById(1L)).thenReturn(existingRequirement);
            when(designWorkLogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of());

            assertThatThrownBy(() -> requirementService.executeStageAction(1L, "start_design"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("请先配置设计规划");
        }

        @Test
        @DisplayName("开发中存在未完成任务时不能提测")
        void executeStageAction_startTest_withIncompleteTasks_throwsException() {
            Task task = new Task();
            task.setStatus("进行中");
            existingRequirement.setStatus("开发中");
            when(requirementMapper.selectById(1L)).thenReturn(existingRequirement);
            when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of(task));

            assertThatThrownBy(() -> requirementService.executeStageAction(1L, "start_test"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("所有任务完成后才能提测");
        }

        @Test
        @DisplayName("开发中全部任务完成后可以提测")
        void executeStageAction_startTest_success() {
            Task task = new Task();
            task.setStatus("已完成");
            existingRequirement.setStatus("开发中");
            when(requirementMapper.selectById(1L)).thenReturn(existingRequirement);
            when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of(task));

            Requirement result = requirementService.executeStageAction(1L, "start_test");

            assertThat(result.getStatus()).isEqualTo("测试中");
            verify(requirementMapper).updateById(existingRequirement);
        }

        @Test
        @DisplayName("测试通过后进入待上线")
        void executeStageAction_testPass_success() {
            existingRequirement.setStatus("测试中");
            when(requirementMapper.selectById(1L)).thenReturn(existingRequirement);

            Requirement result = requirementService.executeStageAction(1L, "test_pass");

            assertThat(result.getStatus()).isEqualTo("待上线");
        }

        @Test
        @DisplayName("待上线确认上线后记录实际上线日期")
        void executeStageAction_goOnline_success() {
            existingRequirement.setStatus("待上线");
            when(requirementMapper.selectById(1L)).thenReturn(existingRequirement);

            Requirement result = requirementService.executeStageAction(1L, "go_online");

            assertThat(result.getStatus()).isEqualTo("已上线");
            assertThat(result.getActualOnlineDate()).isEqualTo(LocalDate.now());
        }
    }

    @Nested
    @DisplayName("list 方法测试")
    class ListTests {

        @Test
        @DisplayName("分页查询需求列表成功")
        void list_withPagination_success() {
            // given
            Page<Requirement> mockPage = new Page<>(1, 10);
            mockPage.setRecords(java.util.List.of(existingRequirement));
            mockPage.setTotal(1);

            when(requirementMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(mockPage);

            // when
            Page<Requirement> result = requirementService.list(1, 10, null, null, null, null, null, null);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getTotal()).isEqualTo(1);
        }

        @Test
        @DisplayName("按业务线筛选需求")
        void list_withBusinessLineFilter_success() {
            // given
            Page<Requirement> mockPage = new Page<>(1, 10);
            mockPage.setRecords(java.util.List.of(existingRequirement));
            mockPage.setTotal(1);

            when(requirementMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(mockPage);

            // when
            Page<Requirement> result = requirementService.list(1, 10, 1L, null, null, null, null, null);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getRecords()).hasSize(1);
        }

        @Test
        @DisplayName("按标题模糊查询需求")
        void list_withTitleSearch_success() {
            // given
            Page<Requirement> mockPage = new Page<>(1, 10);
            mockPage.setRecords(java.util.List.of(existingRequirement));
            mockPage.setTotal(1);

            when(requirementMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(mockPage);

            // when
            Page<Requirement> result = requirementService.list(1, 10, null, null, null, null, null, "已存在");

            // then
            assertThat(result).isNotNull();
            assertThat(result.getRecords()).hasSize(1);
        }
    }
}
