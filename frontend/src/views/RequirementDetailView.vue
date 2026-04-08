<script setup lang="ts">
import { ref, computed, reactive } from 'vue'
import { useRouter } from 'vue-router'
import type { Requirement } from '@/types/requirement'

const router = useRouter()

// 完整生命周期状态流转
const statusFlowSteps = [
  { status: '待评估', label: '待评估' },
  { status: '评估中', label: '评估中' },
  { status: '待设计', label: '待设计' },
  { status: '设计中', label: '设计中' },
  { status: '待确认', label: '待确认' },
  { status: '开发中', label: '开发中' },
  { status: '测试中', label: '测试中' },
  { status: '待上线', label: '待上线' },
  { status: '已上线', label: '已上线' },
  { status: '已交付', label: '已交付' },
  { status: '已验收', label: '已验收' }
]

const lifecycleOrder = [
  '待评估', '评估中', '待设计', '设计中', '待确认',
  '开发中', '测试中', '待上线', '已上线', '已交付', '已验收'
]

const requirement = ref<Requirement>({
  id: '1',
  reqNo: 'REQ20260401001',
  title: '数据看板 - 用户分析模块优化',
  project: '皇家宠物',
  subProject: '数据看板',
  businessLine: '全渠道云鹿定制',
  type: '产品需求',
  status: '评估中',
  statusClass: 'evaluating',
  priority: '高',
  priorityClass: 'high',
  owner: '张三',
  createdAt: '2026-04-01',
  source: '内部',
  requester: '李经理',
  expectDate: '2026-04-30',
  description: `<p>需要优化数据看板的用户分析模块，包括：</p>
<ul>
<li>新增用户留存分析图表</li>
<li>优化TOP10用户排名展示</li>
<li>添加实时数据刷新功能</li>
</ul>
<p>预期达到的效果：提升数据可视化程度，帮助运营人员更好地分析用户行为。</p>`,
  attachments: [
    { id: '1', name: '需求文档.pdf', size: '2.3MB', type: 'doc' },
    { id: '2', name: '原型图.png', size: '5.1MB', type: 'image' }
  ],
  logs: [
    { id: '1', action: '需求状态变更为 评估中', user: '系统', time: '2026-04-02 10:00' },
    { id: '2', action: '评估信息已更新 - 预估工时：16人天', user: '李四', time: '2026-04-02 14:30' },
    { id: '3', action: '需求创建', user: '张三', time: '2026-04-01 10:30' }
  ],
  evaluation: { isFeasible: true, estimatedWorkload: '16人天', estimatedCost: undefined, estimatedOnlineDate: '2026-04-30', evaluator: '李四' },
  designWorks: [],
  tasks: []
})

const isStepCompleted = (stepStatus: string) => {
  if (requirement.value.status === '已拒绝') {
    return lifecycleOrder.indexOf(stepStatus) < lifecycleOrder.indexOf('评估中')
  }
  const currentIndex = lifecycleOrder.indexOf(requirement.value.status)
  const stepIndex = lifecycleOrder.indexOf(stepStatus)
  return stepIndex < currentIndex
}

const showEvaluation = computed(() => {
  return ['待评估', '评估中', '已拒绝'].includes(requirement.value.status)
})

const showDesign = computed(() => {
  return ['待设计', '设计中', '待确认'].includes(requirement.value.status)
})

const showTasks = computed(() => {
  return ['开发中', '测试中', '待上线', '已上线'].includes(requirement.value.status)
})

interface Action {
  type: string
  label: string
  class: string
}

const getCurrentActions = (): Action[] => {
  const status = requirement.value.status
  const type = requirement.value.type
  const actions: Action[] = []

  switch (status) {
    case '待评估':
      actions.push(
        { type: 'start_eval', label: '开始评估', class: 'primary' },
        { type: 'reject', label: '标记已拒绝', class: 'danger' }
      )
      break
    case '评估中':
      actions.push(
        { type: 'submit_eval', label: '提交评估', class: 'primary' },
        { type: 'reject', label: '标记已拒绝', class: 'danger' }
      )
      break
    case '待设计':
      if (type === '项目需求') {
        actions.push(
          { type: 'start_design', label: '开始设计', class: 'primary' },
          { type: 'convert', label: '转为产品需求', class: 'secondary' }
        )
      } else {
        actions.push(
          { type: 'start_design', label: '开始设计', class: 'primary' }
        )
      }
      break
    case '设计中':
      actions.push(
        { type: 'add_design', label: '添设计工作', class: 'secondary' },
        { type: 'confirm', label: '确认完成', class: 'primary' }
      )
      break
    case '待确认':
      actions.push(
        { type: 'customer_confirm', label: '客户确认', class: 'primary' },
        { type: 'internal_confirm', label: '内部确认', class: 'secondary' }
      )
      break
    case '开发中':
      actions.push(
        { type: 'add_task', label: '添加任务', class: 'secondary' },
        { type: 'start_test', label: '提测', class: 'primary' }
      )
      break
    case '测试中':
      actions.push(
        { type: 'test_pass', label: '测试通过', class: 'primary' },
        { type: 'test_fail', label: '测试失败', class: 'danger' }
      )
      break
    case '待上线':
      actions.push(
        { type: 'go_online', label: '确认上线', class: 'primary' }
      )
      break
    case '已上线':
      if (type === '项目需求') {
        actions.push(
          { type: 'deliver', label: '确认交付', class: 'primary' }
        )
      }
      break
    case '已交付':
      if (type === '项目需求') {
        actions.push(
          { type: 'accept', label: '确认验收', class: 'primary' }
        )
      }
      break
  }
  return actions
}

const executeAction = (type: string) => {
  if (type === 'submit_eval') {
    evaluationForm.isFeasible = true
    evaluationForm.estimatedWorkload = ''
    evaluationForm.estimatedCost = ''
    evaluationForm.estimatedOnlineDate = ''
    showEvaluationModal.value = true
    return
  }
  alert(`执行操作: ${type}`)
}

const showEvaluationModal = ref(false)
const evaluationForm = reactive({
  isFeasible: true,
  estimatedWorkload: '',
  estimatedCost: '',
  estimatedOnlineDate: ''
})

const submitEvaluation = () => {
  if (!evaluationForm.estimatedWorkload) {
    alert('请输入预估工时')
    return
  }
  if (!evaluationForm.estimatedOnlineDate) {
    alert('请选择预估上线时间')
    return
  }
  if (requirement.value.type === '项目需求' && !evaluationForm.estimatedCost) {
    alert('项目需求请输入预估报价')
    return
  }

  requirement.value.evaluation = {
    isFeasible: evaluationForm.isFeasible,
    estimatedWorkload: evaluationForm.estimatedWorkload ? evaluationForm.estimatedWorkload + '人天' : '',
    estimatedCost: evaluationForm.estimatedCost ? '¥' + evaluationForm.estimatedCost : undefined,
    estimatedOnlineDate: evaluationForm.estimatedOnlineDate,
    evaluator: '当前用户'
  }

  const newLog = {
    id: String((requirement.value.logs?.length || 0) + 1),
    action: `评估信息已更新 - 预估工时：${evaluationForm.estimatedWorkload}人天`,
    user: '当前用户',
    time: new Date().toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    }).replace(/\//g, '-')
  }
  if (requirement.value.logs) {
    requirement.value.logs.unshift(newLog)
  }

  showEvaluationModal.value = false
  alert('评估信息已提交')
}

const goBack = () => {
  router.push('/requirements')
}

const getStatusBadgeClass = (status: string) => {
  const map: Record<string, string> = {
    '待评估': 'pending',
    '评估中': 'evaluating',
    '待设计': 'pending_design',
    '设计中': 'designing',
    '待确认': 'pending_confirm',
    '开发中': 'developing',
    '测试中': 'testing',
    '待上线': 'testing',
    '已上线': 'online',
    '已交付': 'delivered',
    '已验收': 'accepted',
    '已拒绝': 'rejected'
  }
  return map[status] || 'pending'
}
</script>

<template>
  <div class="detail-page">
    <!-- 头部 -->
    <div class="detail-header">
      <div class="header-left">
        <button class="back-btn" @click="goBack">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M19 12H5M12 19l-7-7 7-7"/>
          </svg>
        </button>
        <div class="header-info">
          <span class="req-no">{{ requirement.reqNo }}</span>
          <h1 class="requirement-title">{{ requirement.title }}</h1>
          <div class="tag-list" style="margin-top: 8px;">
            <span class="tag-item">{{ requirement.project }}</span>
            <span v-if="requirement.subProject" class="tag-item">{{ requirement.subProject }}</span>
            <span class="tag-item">{{ requirement.businessLine }}</span>
            <span :class="['status-badge', getStatusBadgeClass(requirement.status)]">{{ requirement.status }}</span>
          </div>
        </div>
      </div>
      <div class="header-actions">
        <button class="btn btn-default btn-sm">编辑</button>
        <button class="btn btn-primary btn-sm">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
            <polyline points="7 10 12 15 17 10"/>
            <line x1="12" y1="15" x2="12" y2="3"/>
          </svg>
          导出
        </button>
      </div>
    </div>

    <!-- 状态流转 -->
    <div class="status-flow">
      <div
        v-for="(step, index) in statusFlowSteps"
        :key="step.status"
        class="status-flow-item"
        :class="{
          completed: isStepCompleted(step.status),
          current: requirement.status === step.status,
          rejected: step.status === '已拒绝' && requirement.status === '已拒绝'
        }"
      >
        <div class="status-dot">{{ index + 1 }}</div>
        <div class="status-label">{{ step.label }}</div>
      </div>
    </div>

    <!-- 操作面板 -->
    <div class="action-panel" v-if="getCurrentActions().length > 0">
      <div class="action-panel-title">可执行操作</div>
      <div class="action-buttons">
        <button
          v-for="action in getCurrentActions()"
          :key="action.type"
          :class="['action-btn', action.class]"
          @click="executeAction(action.type)"
        >
          {{ action.label }}
        </button>
      </div>
    </div>

    <!-- 主内容 -->
    <div class="detail-body">
      <div class="detail-main">
        <!-- 评估信息 -->
        <div class="content-section" v-if="showEvaluation">
          <div class="content-section-header">
            <h3 class="content-section-title">评估信息</h3>
          </div>
          <div class="evaluation-info">
            <div class="evaluation-item">
              <span class="evaluation-label">技术可行性</span>
              <span class="evaluation-value" :class="requirement.evaluation?.isFeasible ? 'success' : 'danger'">
                {{ requirement.evaluation?.isFeasible ? '可行' : '不可行' }}
              </span>
            </div>
            <div class="evaluation-item">
              <span class="evaluation-label">预估工时</span>
              <span class="evaluation-value">{{ requirement.evaluation?.estimatedWorkload || '-' }}</span>
            </div>
            <div class="evaluation-item" v-if="requirement.type === '项目需求'">
              <span class="evaluation-label">预估报价</span>
              <span class="evaluation-value">{{ requirement.evaluation?.estimatedCost || '-' }}</span>
            </div>
            <div class="evaluation-item">
              <span class="evaluation-label">预估上线</span>
              <span class="evaluation-value">{{ requirement.evaluation?.estimatedOnlineDate || '-' }}</span>
            </div>
            <div class="evaluation-item">
              <span class="evaluation-label">评估人</span>
              <span class="evaluation-value">{{ requirement.evaluation?.evaluator || '-' }}</span>
            </div>
          </div>
        </div>

        <!-- 设计工作 -->
        <div class="content-section" v-if="showDesign">
          <div class="content-section-header">
            <h3 class="content-section-title">设计工作</h3>
            <span class="content-section-action" v-if="requirement.status === '设计中'">添加</span>
          </div>
          <div class="design-works">
            <div class="design-work-item" v-for="work in requirement.designWorks" :key="work.type">
              <div class="design-work-info">
                <div class="design-work-type">{{ work.type }}</div>
                <div class="design-work-meta">
                  预估: {{ work.estimatedHours || '-' }}h | 实际: {{ work.actualHours || '-' }}h
                  <span v-if="work.designer"> | 设计人: {{ work.designer }}</span>
                </div>
              </div>
              <span :class="['design-work-status', work.status]">{{ work.statusText }}</span>
            </div>
            <div v-if="!requirement.designWorks?.length" class="no-data">暂无设计工作</div>
          </div>
        </div>

        <!-- 开发任务 -->
        <div class="content-section" v-if="showTasks">
          <div class="content-section-header">
            <h3 class="content-section-title">开发任务</h3>
            <span class="content-section-action" v-if="requirement.status === '开发中'">添加</span>
          </div>
          <div class="task-list">
            <div class="task-item" v-for="task in requirement.tasks" :key="task.id">
              <div class="task-checkbox" :class="{ done: task.status === '已完成' }">
                <svg v-if="task.status === '已完成'" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                  <polyline points="20 6 9 17 4 12"/>
                </svg>
              </div>
              <div class="task-info">
                <div class="task-title" :class="{ done: task.status === '已完成' }">{{ task.title }}</div>
                <div class="task-meta">{{ task.type }} | {{ task.estimatedHours || '-' }}h | {{ task.assignee }}</div>
              </div>
              <div class="task-assignee">{{ task.assignee?.charAt(0) }}</div>
            </div>
            <div v-if="!requirement.tasks?.length" class="no-data">暂无任务</div>
          </div>
        </div>

        <!-- 需求描述 -->
        <div class="content-section">
          <div class="content-section-header">
            <h3 class="content-section-title">需求描述</h3>
          </div>
          <div class="description-content" v-html="requirement.description || '暂无描述'"></div>
        </div>

        <!-- 附件 -->
        <div class="content-section">
          <div class="content-section-header">
            <h3 class="content-section-title">附件</h3>
            <span class="content-section-action">上传</span>
          </div>
          <div class="attachment-list" v-if="requirement.attachments && requirement.attachments.length">
            <div class="attachment-item" v-for="att in requirement.attachments" :key="att.id">
              <span class="attachment-icon">{{ att.type === 'image' ? '🖼️' : '📄' }}</span>
              <span class="attachment-name">{{ att.name }}</span>
              <span class="attachment-size">{{ att.size }}</span>
            </div>
          </div>
          <div v-else class="no-data">暂无附件</div>
        </div>

        <!-- 活动日志 -->
        <div class="content-section">
          <div class="content-section-header">
            <h3 class="content-section-title">活动日志</h3>
          </div>
          <div class="timeline" v-if="requirement.logs && requirement.logs.length">
            <div class="timeline-item" v-for="log in requirement.logs" :key="log.id">
              <div class="timeline-dot"></div>
              <div class="timeline-content">
                <div class="timeline-text">{{ log.action }}</div>
                <div class="timeline-meta">
                  <span class="timeline-user">{{ log.user }}</span>
                  <span>{{ log.time }}</span>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="no-data">暂无日志记录</div>
        </div>
      </div>

      <!-- 侧边栏 -->
      <div class="detail-sidebar">
        <div class="info-card">
          <div class="info-card-title">基本信息</div>
          <div class="info-item">
            <span class="info-label">需求类型</span>
            <span class="info-value">{{ requirement.type }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">优先级</span>
            <span class="info-value">
              <span :class="['priority-text', requirement.priorityClass]">{{ requirement.priority }}</span>
            </span>
          </div>
          <div class="info-item">
            <span class="info-label">负责人</span>
            <span class="info-value">{{ requirement.owner }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">期望上线</span>
            <span class="info-value">{{ requirement.expectDate || '-' }}</span>
          </div>
        </div>

        <div class="info-card">
          <div class="info-card-title">来源信息</div>
          <div class="info-item">
            <span class="info-label">需求来源</span>
            <span class="info-value">{{ requirement.source || '内部' }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">提出人</span>
            <span class="info-value">{{ requirement.requester || '-' }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">创建时间</span>
            <span class="info-value">{{ requirement.createdAt }}</span>
          </div>
        </div>

        <div class="info-card">
          <div class="info-card-title">项目信息</div>
          <div class="info-item">
            <span class="info-label">所属业务线</span>
            <span class="info-value">{{ requirement.businessLine }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">所属项目</span>
            <span class="info-value">{{ requirement.project }}</span>
          </div>
          <div class="info-item" v-if="requirement.subProject">
            <span class="info-label">子项目</span>
            <span class="info-value">{{ requirement.subProject }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 评估表单弹窗 -->
    <div class="modal-overlay" v-if="showEvaluationModal" @click.self="showEvaluationModal = false">
      <div class="modal" style="width: 480px;">
        <div class="modal-header">
          <h3 class="modal-title">提交评估</h3>
          <button class="modal-close" @click="showEvaluationModal = false">×</button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label class="form-label">技术可行性 <span class="required">*</span></label>
            <div class="radio-group">
              <label class="radio-item" :class="{ active: evaluationForm.isFeasible }">
                <input type="radio" v-model="evaluationForm.isFeasible" :value="true">
                <span>可行</span>
              </label>
              <label class="radio-item" :class="{ active: !evaluationForm.isFeasible }">
                <input type="radio" v-model="evaluationForm.isFeasible" :value="false">
                <span>不可行</span>
              </label>
            </div>
          </div>
          <div class="form-group">
            <label class="form-label">预估工时 <span class="required">*</span></label>
            <div style="display: flex; align-items: center; gap: 8px;">
              <input
                type="number"
                class="form-input"
                v-model="evaluationForm.estimatedWorkload"
                placeholder="请输入"
                min="1"
                style="flex: 1;"
              >
              <span style="color: var(--gray-500); font-size: 13px;">人天</span>
            </div>
          </div>
          <div class="form-group" v-if="requirement.type === '项目需求'">
            <label class="form-label">预估报价 <span class="required">*</span></label>
            <div style="display: flex; align-items: center; gap: 8px;">
              <span style="color: var(--gray-500); font-size: 13px;">¥</span>
              <input
                type="number"
                class="form-input"
                v-model="evaluationForm.estimatedCost"
                placeholder="请输入"
                min="0"
                style="flex: 1;"
              >
              <span style="color: var(--gray-500); font-size: 13px;">元</span>
            </div>
          </div>
          <div class="form-group">
            <label class="form-label">预估上线时间 <span class="required">*</span></label>
            <input type="date" class="form-input" v-model="evaluationForm.estimatedOnlineDate">
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-default" @click="showEvaluationModal = false">取消</button>
          <button class="btn btn-primary" @click="submitEvaluation">提交评估</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.detail-page {
  padding: 24px;
}

/* 头部 */
.detail-header {
  background: #fff;
  border-radius: var(--radius-lg);
  padding: 20px 24px;
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.back-btn {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  border: none;
  background: var(--gray-100);
  color: var(--gray-600);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;
}

.back-btn:hover {
  background: var(--gray-200);
  color: var(--gray-800);
}

.header-info {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.req-no {
  font-size: 13px;
  color: var(--gray-500);
  font-family: monospace;
}

.requirement-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--gray-800);
  margin: 0;
}

.header-actions {
  display: flex;
  gap: 8px;
}

/* 状态流转 */
.status-flow {
  display: flex;
  align-items: center;
  padding: 20px 24px;
  background: #fff;
  border-radius: var(--radius-lg);
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
  overflow-x: auto;
}

.status-flow-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  min-width: 70px;
  position: relative;
}

.status-flow-item:not(:last-child)::after {
  content: '';
  position: absolute;
  top: 14px;
  left: calc(50% + 18px);
  width: calc(100% - 36px);
  height: 2px;
  background: var(--gray-200);
}

.status-flow-item.completed:not(:last-child)::after {
  background: var(--success);
}

.status-flow-item.current:not(:last-child)::after {
  background: linear-gradient(to right, var(--primary) 50%, var(--gray-200) 50%);
}

.status-dot {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--gray-200);
  color: var(--gray-500);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  position: relative;
  z-index: 1;
}

.status-flow-item.completed .status-dot {
  background: var(--success);
  color: white;
}

.status-flow-item.current .status-dot {
  background: var(--primary);
  color: white;
  box-shadow: 0 0 0 4px rgba(99, 102, 241, 0.2);
}

.status-flow-item.rejected .status-dot {
  background: var(--gray-300);
  color: var(--gray-500);
}

.status-label {
  font-size: 11px;
  color: var(--gray-500);
  text-align: center;
  white-space: nowrap;
}

.status-flow-item.current .status-label {
  color: var(--primary);
  font-weight: 600;
}

.status-flow-item.completed .status-label {
  color: var(--success);
}

/* 操作面板 */
.action-panel {
  background: var(--gray-50);
  border-radius: var(--radius-lg);
  padding: 16px 20px;
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
}

.action-panel-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-700);
  margin-bottom: 12px;
}

.action-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.action-btn {
  padding: 8px 16px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border: none;
  transition: all 0.15s ease;
}

.action-btn.primary {
  background: var(--primary);
  color: white;
}

.action-btn.primary:hover {
  background: #4338CA;
}

.action-btn.secondary {
  background: #fff;
  color: var(--gray-700);
  border: 1px solid var(--gray-200);
}

.action-btn.secondary:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.action-btn.danger {
  background: #FEE2E2;
  color: var(--danger);
}

.action-btn.danger:hover {
  background: #FECACA;
}

/* 主内容区 */
.detail-body {
  display: flex;
  gap: 16px;
}

.detail-main {
  flex: 1;
  min-width: 0;
}

.detail-sidebar {
  width: 300px;
  flex-shrink: 0;
}

/* 内容区块 */
.content-section {
  background: #fff;
  border-radius: var(--radius-lg);
  padding: 20px;
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
}

.content-section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--gray-100);
}

.content-section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--gray-800);
  margin: 0;
}

.content-section-action {
  font-size: 13px;
  color: var(--primary);
  cursor: pointer;
}

.content-section-action:hover {
  text-decoration: underline;
}

/* 描述内容 */
.description-content {
  font-size: 14px;
  color: var(--gray-700);
  line-height: 1.7;
}

.description-content p {
  margin-bottom: 12px;
}

.description-content ul {
  padding-left: 20px;
  margin-bottom: 12px;
}

.description-content li {
  margin-bottom: 6px;
}

/* 附件 */
.attachment-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.attachment-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: var(--gray-50);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.15s ease;
}

.attachment-item:hover {
  background: var(--gray-100);
}

.attachment-icon {
  font-size: 20px;
}

.attachment-name {
  flex: 1;
  font-size: 13px;
  color: var(--gray-800);
}

.attachment-size {
  font-size: 12px;
  color: var(--gray-400);
}

.no-data {
  font-size: 13px;
  color: var(--gray-400);
  text-align: center;
  padding: 24px;
}

/* 时间线 */
.timeline {
  position: relative;
  padding-left: 20px;
}

.timeline::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 8px;
  bottom: 8px;
  width: 2px;
  background: var(--gray-200);
}

.timeline-item {
  position: relative;
  padding-bottom: 16px;
}

.timeline-item:last-child {
  padding-bottom: 0;
}

.timeline-dot {
  position: absolute;
  left: -17px;
  top: 4px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--primary);
  border: 2px solid #fff;
}

.timeline-content {
  background: var(--gray-50);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
}

.timeline-text {
  font-size: 13px;
  color: var(--gray-700);
  margin-bottom: 4px;
}

.timeline-meta {
  display: flex;
  gap: 12px;
  font-size: 11px;
  color: var(--gray-400);
}

.timeline-user {
  color: var(--primary);
}

/* 标签 */
.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag-item {
  padding: 4px 10px;
  background: var(--gray-100);
  border-radius: 12px;
  font-size: 12px;
  color: var(--gray-600);
}

/* 侧边信息卡片 */
.info-card {
  background: #fff;
  border-radius: var(--radius-lg);
  padding: 16px;
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
}

.info-card-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--gray-500);
  margin-bottom: 12px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.info-item {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 10px;
}

.info-item:last-child {
  margin-bottom: 0;
}

.info-label {
  font-size: 13px;
  color: var(--gray-500);
  flex-shrink: 0;
}

.info-value {
  font-size: 13px;
  color: var(--gray-800);
  text-align: right;
  word-break: break-all;
}

.priority-text.high { color: var(--danger); font-weight: 600; }
.priority-text.medium { color: var(--warning); font-weight: 600; }
.priority-text.low { color: var(--gray-500); }

.status-badge {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
}
.status-badge.pending { background: #FEF3C7; color: #B45309; }
.status-badge.evaluating { background: #DBEAFE; color: #1D4ED8; }
.status-badge.designing { background: #D1FAE5; color: #047857; }
.status-badge.developing { background: #FEE2E2; color: #DC2626; }
.status-badge.testing { background: #E9D5FF; color: #7C3AED; }
.status-badge.online { background: #D1FAE5; color: #047857; }
.status-badge.rejected { background: var(--gray-100); color: var(--gray-500); }
.status-badge.pending_design { background: #FEF3C7; color: #B45309; }
.status-badge.pending_confirm { background: #FEF3C7; color: #B45309; }
.status-badge.delivered { background: #D1FAE5; color: #047857; }
.status-badge.accepted { background: #D1FAE5; color: #047857; }

/* 评估信息 */
.evaluation-info {
  background: var(--gray-50);
  border-radius: var(--radius-md);
  padding: 16px;
}

.evaluation-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid var(--gray-100);
}

.evaluation-item:last-child {
  border-bottom: none;
}

.evaluation-label {
  font-size: 13px;
  color: var(--gray-500);
}

.evaluation-value {
  font-size: 13px;
  color: var(--gray-800);
  font-weight: 500;
}

.evaluation-value.success { color: var(--success); }
.evaluation-value.danger { color: var(--danger); }

/* 设计工作 */
.design-works {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.design-work-item {
  background: var(--gray-50);
  border-radius: var(--radius-md);
  padding: 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.design-work-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.design-work-type {
  font-size: 13px;
  font-weight: 500;
  color: var(--gray-800);
}

.design-work-meta {
  font-size: 12px;
  color: var(--gray-500);
}

.design-work-status {
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
}

.design-work-status.pending { background: var(--gray-100); color: var(--gray-600); }
.design-work-status.in-progress { background: #DBEAFE; color: #1D4ED8; }
.design-work-status.done { background: #D1FAE5; color: #047857; }

/* 任务列表 */
.task-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.task-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  background: var(--gray-50);
  border-radius: var(--radius-sm);
}

.task-checkbox {
  width: 18px;
  height: 18px;
  border-radius: 4px;
  border: 2px solid var(--gray-300);
  display: flex;
  align-items: center;
  justify-content: center;
}

.task-checkbox.done {
  background: var(--success);
  border-color: var(--success);
  color: white;
}

.task-info {
  flex: 1;
  min-width: 0;
}

.task-title {
  font-size: 13px;
  color: var(--gray-800);
}

.task-title.done {
  text-decoration: line-through;
  color: var(--gray-400);
}

.task-meta {
  font-size: 11px;
  color: var(--gray-400);
}

.task-assignee {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--primary-light);
  color: var(--primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 600;
}

/* 按钮 */
.btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: var(--radius-md);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  border: none;
  transition: all 0.15s ease;
}

.btn-primary {
  background: var(--primary);
  color: white;
}

.btn-primary:hover {
  background: #4338CA;
}

.btn-default {
  background: #fff;
  color: var(--gray-700);
  border: 1px solid var(--gray-200);
}

.btn-default:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.btn-sm {
  padding: 6px 12px;
  font-size: 13px;
}

/* 弹窗样式 */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal {
  background: #fff;
  border-radius: var(--radius-lg);
  max-width: 90vw;
  max-height: 90vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--gray-200);
}

.modal-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--gray-800);
}

.modal-close {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  border: none;
  background: transparent;
  color: var(--gray-500);
  font-size: 24px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal-close:hover {
  background: var(--gray-100);
  color: var(--gray-700);
}

.modal-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow-y: auto;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 12px 20px;
  border-top: 1px solid var(--gray-200);
  background: var(--gray-50);
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--gray-700);
}

.required {
  color: var(--danger);
}

.form-input {
  padding: 8px 12px;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-sm);
  font-size: 14px;
  outline: none;
}

.form-input:focus {
  border-color: var(--primary);
}

.radio-group {
  display: flex;
  gap: 8px;
}

.radio-item {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px;
  border: 1.5px solid var(--gray-200);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 13px;
  color: var(--gray-600);
  transition: all 0.15s ease;
}

.radio-item input {
  display: none;
}

.radio-item:hover {
  border-color: var(--gray-300);
}

.radio-item.active {
  border-color: var(--primary);
  background: var(--primary-light);
  color: var(--primary);
}
</style>
