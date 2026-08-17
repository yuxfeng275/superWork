<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api } from '@/utils/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { WORKFLOW_ROLE_OPTIONS } from '@/constants/roles'

interface WorkflowConfig {
  id: number
  requirementType: string
  currentStatus?: string
  nextStatus?: string
  transitionName?: string
  fromStatus?: string
  toStatus?: string
  allowedRoles?: string | string[]
  conditionType?: string | null
  isActive?: number
  sortOrder?: number
  createdAt?: string
}

interface NormalizedWorkflowConfig extends WorkflowConfig {
  currentStatus: string
  nextStatus: string
  allowedRolesList: string[]
  transitionLabel: string
}

interface NodePosition {
  x: number
  y: number
}

interface EdgeBadge {
  id: number
  left: number
  top: number
  label: string
}

interface RenderedEdge extends NormalizedWorkflowConfig {
  startX: number
  startY: number
  endX: number
  endY: number
}

const CANVAS_WIDTH = 1280
const CANVAS_HEIGHT = 680
const NODE_WIDTH = 180
const NODE_HEIGHT = 88
const LAYOUT_STORAGE_KEY = 'workflow-canvas-layouts-v1'

const ROLE_OPTIONS = WORKFLOW_ROLE_OPTIONS

const loading = ref(false)
const activeRequirementType = ref('项目需求')
const rawConfigs = ref<NormalizedWorkflowConfig[]>([])
const statusOptions = ref<Record<string, string[]>>({})
const layouts = ref<Record<string, Record<string, NodePosition>>>({})
const canvasRef = ref<HTMLElement | null>(null)

const dialogVisible = ref(false)
const isEdit = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref()
const form = ref({
  requirementType: '',
  fromStatus: '',
  toStatus: '',
  conditionType: '',
  allowedRoles: ['解决方案经理'] as string[],
  isActive: 1,
  sortOrder: 0
})

const dragState = ref<{
  status: string
  offsetX: number
  offsetY: number
} | null>(null)

const pendingConnection = ref<{
  fromStatus: string
  startX: number
  startY: number
  currentX: number
  currentY: number
} | null>(null)

const rules = {
  conditionType: [{ required: true, message: '请输入流转说明', trigger: 'blur' }],
  allowedRoles: [{ required: true, message: '请选择至少一个角色', trigger: 'change' }]
}

const normalizeAllowedRoles = (allowedRoles: WorkflowConfig['allowedRoles']) => {
  if (Array.isArray(allowedRoles)) return allowedRoles
  if (typeof allowedRoles === 'string') {
    try {
      const parsed = JSON.parse(allowedRoles)
      return Array.isArray(parsed) ? parsed : []
    } catch {
      return []
    }
  }
  return []
}

const normalizeWorkflowConfig = (config: WorkflowConfig): NormalizedWorkflowConfig => {
  const currentStatus = config.fromStatus || config.currentStatus || ''
  const nextStatus = config.toStatus || config.nextStatus || ''
  const allowedRolesList = normalizeAllowedRoles(config.allowedRoles)

  return {
    ...config,
    currentStatus,
    nextStatus,
    allowedRolesList,
    transitionLabel: config.transitionName || config.conditionType || '手动流转'
  }
}

const typeOptions = computed(() => {
  const keys = Object.keys(statusOptions.value)
  if (keys.length > 0) return keys
  return Array.from(new Set(rawConfigs.value.map(item => item.requirementType)))
})

const activeStatuses = computed(() => {
  return statusOptions.value[activeRequirementType.value] || []
})

const activeTransitions = computed(() => {
  return rawConfigs.value
    .filter(item => item.requirementType === activeRequirementType.value)
    .sort((left, right) => (left.sortOrder ?? 0) - (right.sortOrder ?? 0))
})

const activeLayout = computed(() => {
  const current = layouts.value[activeRequirementType.value] || {}
  const layout: Record<string, NodePosition> = { ...current }

  activeStatuses.value.forEach((status, index) => {
    if (!layout[status]) {
      const col = index % 4
      const row = Math.floor(index / 4)
      layout[status] = {
        x: 80 + col * 260,
        y: 110 + row * 180
      }
    }
  })

  return layout
})

const activeEdges = computed<RenderedEdge[]>(() => {
  return activeTransitions.value.map(transition => {
    const from = activeLayout.value[transition.currentStatus]
    const to = activeLayout.value[transition.nextStatus]
    if (!from || !to) return null

    return {
      ...transition,
      startX: from.x + NODE_WIDTH,
      startY: from.y + NODE_HEIGHT / 2,
      endX: to.x,
      endY: to.y + NODE_HEIGHT / 2
    }
  }).filter((edge): edge is RenderedEdge => edge !== null)
})

const edgeBadges = computed<EdgeBadge[]>(() => {
  return activeEdges.value.map(edge => ({
    id: edge.id,
    left: (edge.startX + edge.endX) / 2 - 56,
    top: (edge.startY + edge.endY) / 2 - 18,
    label: edge.transitionLabel
  }))
})

const pendingPath = computed(() => {
  if (!pendingConnection.value) return ''
  const { startX, startY, currentX, currentY } = pendingConnection.value
  return `M ${startX} ${startY} L ${currentX} ${currentY}`
})

const saveLayouts = () => {
  localStorage.setItem(LAYOUT_STORAGE_KEY, JSON.stringify(layouts.value))
}

const loadLayouts = () => {
  try {
    const raw = localStorage.getItem(LAYOUT_STORAGE_KEY)
    layouts.value = raw ? JSON.parse(raw) : {}
  } catch {
    layouts.value = {}
  }
}

const ensureTypeLayout = (requirementType: string, statuses: string[]) => {
  if (!layouts.value[requirementType]) {
    layouts.value[requirementType] = {}
  }

  statuses.forEach((status, index) => {
    if (!layouts.value[requirementType][status]) {
      const col = index % 4
      const row = Math.floor(index / 4)
      layouts.value[requirementType][status] = {
        x: 80 + col * 260,
        y: 110 + row * 180
      }
    }
  })

  saveLayouts()
}

const loadData = async () => {
  loading.value = true
  try {
    const [configs, options] = await Promise.all([
      api.getWorkflowConfigs(),
      api.getWorkflowStatusOptions()
    ])

    rawConfigs.value = Array.isArray(configs) ? configs.map(normalizeWorkflowConfig) : []
    statusOptions.value = options || {}

    const typeKeys = Object.keys(statusOptions.value)
    typeKeys.forEach(type => ensureTypeLayout(type, statusOptions.value[type] || []))

    if (typeKeys.length > 0 && !typeKeys.includes(activeRequirementType.value)) {
      activeRequirementType.value = typeKeys[0]
    }
  } catch (error) {
    console.error('加载工作流配置失败:', error)
    ElMessage.error('加载工作流配置失败')
  } finally {
    loading.value = false
  }
}

const openCreateDialog = (fromStatus: string, toStatus: string) => {
  form.value = {
    requirementType: activeRequirementType.value,
    fromStatus,
    toStatus,
    conditionType: '',
    allowedRoles: ['解决方案经理'],
    isActive: 1,
    sortOrder: activeTransitions.value.length + 1
  }
  editingId.value = null
  isEdit.value = false
  dialogVisible.value = true
}

const openEditDialog = (transition: NormalizedWorkflowConfig) => {
  form.value = {
    requirementType: transition.requirementType,
    fromStatus: transition.currentStatus,
    toStatus: transition.nextStatus,
    conditionType: transition.conditionType || transition.transitionLabel,
    allowedRoles: transition.allowedRolesList.length ? transition.allowedRolesList : ['解决方案经理'],
    isActive: transition.isActive ?? 1,
    sortOrder: transition.sortOrder ?? 0
  }
  editingId.value = transition.id
  isEdit.value = true
  dialogVisible.value = true
}

const handleDelete = async (transition: NormalizedWorkflowConfig) => {
  try {
    await ElMessageBox.confirm('确定要删除这条流转规则吗？', '提示', { type: 'warning' })
    await api.deleteWorkflowConfig(transition.id)
    ElMessage.success('删除成功')
    await loadData()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

const handleDeleteCurrent = async () => {
  if (!editingId.value) return
  const transition = activeTransitions.value.find(item => item.id === editingId.value)
  if (!transition) return
  dialogVisible.value = false
  await handleDelete(transition)
}

const handleSubmit = async () => {
  if (!formRef.value) return

  await formRef.value.validate(async (valid: boolean) => {
    if (!valid) return

    const payload = {
      requirementType: form.value.requirementType,
      fromStatus: form.value.fromStatus,
      toStatus: form.value.toStatus,
      allowedRoles: form.value.allowedRoles,
      conditionType: form.value.conditionType,
      isActive: form.value.isActive,
      sortOrder: form.value.sortOrder
    }

    try {
      if (editingId.value) {
        await api.updateWorkflowConfig(editingId.value, payload)
        ElMessage.success('更新成功')
      } else {
        await api.createWorkflowConfig(payload)
        ElMessage.success('创建成功')
      }

      dialogVisible.value = false
      await loadData()
    } catch (error) {
      ElMessage.error(editingId.value ? '更新失败' : '创建失败')
    }
  })
}

const getCanvasPoint = (event: PointerEvent) => {
  const rect = canvasRef.value?.getBoundingClientRect()
  if (!rect) return { x: 0, y: 0 }
  return {
    x: event.clientX - rect.left,
    y: event.clientY - rect.top
  }
}

const startNodeDrag = (event: PointerEvent, status: string) => {
  const point = getCanvasPoint(event)
  const current = activeLayout.value[status]
  if (!current) return

  dragState.value = {
    status,
    offsetX: point.x - current.x,
    offsetY: point.y - current.y
  }
}

const startConnection = (event: PointerEvent, status: string) => {
  event.stopPropagation()
  const position = activeLayout.value[status]
  if (!position) return

  pendingConnection.value = {
    fromStatus: status,
    startX: position.x + NODE_WIDTH,
    startY: position.y + NODE_HEIGHT / 2,
    currentX: position.x + NODE_WIDTH,
    currentY: position.y + NODE_HEIGHT / 2
  }
}

const finishConnection = (targetStatus: string) => {
  const pending = pendingConnection.value
  if (!pending || pending.fromStatus === targetStatus) {
    pendingConnection.value = null
    return
  }

  openCreateDialog(pending.fromStatus, targetStatus)
  pendingConnection.value = null
}

const handlePointerMove = (event: PointerEvent) => {
  if (dragState.value) {
    const point = getCanvasPoint(event)
    const typeLayout = layouts.value[activeRequirementType.value] || {}
    typeLayout[dragState.value.status] = {
      x: Math.max(24, Math.min(point.x - dragState.value.offsetX, CANVAS_WIDTH - NODE_WIDTH - 24)),
      y: Math.max(24, Math.min(point.y - dragState.value.offsetY, CANVAS_HEIGHT - NODE_HEIGHT - 24))
    }
    layouts.value[activeRequirementType.value] = typeLayout
    saveLayouts()
  }

  if (pendingConnection.value) {
    const point = getCanvasPoint(event)
    pendingConnection.value = {
      ...pendingConnection.value,
      currentX: point.x,
      currentY: point.y
    }
  }
}

const stopInteractions = () => {
  dragState.value = null
  pendingConnection.value = null
}

watch(activeRequirementType, type => {
  const statuses = statusOptions.value[type] || []
  ensureTypeLayout(type, statuses)
})

onMounted(() => {
  loadLayouts()
  loadData()
  window.addEventListener('pointermove', handlePointerMove)
  window.addEventListener('pointerup', stopInteractions)
})

onUnmounted(() => {
  window.removeEventListener('pointermove', handlePointerMove)
  window.removeEventListener('pointerup', stopInteractions)
})
</script>

<template>
  <div class="workflow-view">
    <div class="content-header">
      <div class="title-with-stats">
        <h2 class="page-title">工作流配置</h2>
        <div class="inline-stats">
          <span class="inline-stat">
            <span class="stat-num">{{ rawConfigs.length }}</span>
            <span class="stat-text">条规则</span>
          </span>
          <span class="inline-stat">
            <span class="stat-num">{{ activeStatuses.length }}</span>
            <span class="stat-text">个状态</span>
          </span>
        </div>
      </div>
      <div class="page-actions">
        <span class="helper-copy">拖拽节点调整布局，从右侧圆点拖到目标状态即可创建流转。</span>
      </div>
    </div>

    <div class="type-strip">
      <button
        v-for="type in typeOptions"
        :key="type"
        type="button"
        class="type-chip"
        :class="{ active: activeRequirementType === type }"
        @click="activeRequirementType = type"
      >
        {{ type }}
      </button>
    </div>

    <div class="workflow-stage" v-loading="loading">
      <el-empty v-if="!loading && activeStatuses.length === 0" description="暂无工作流状态可配置" style="padding: 80px 0" />

      <div
        v-else
        ref="canvasRef"
        class="canvas-board"
        :style="{ width: `${CANVAS_WIDTH}px`, height: `${CANVAS_HEIGHT}px` }"
      >
        <svg class="canvas-lines" :viewBox="`0 0 ${CANVAS_WIDTH} ${CANVAS_HEIGHT}`">
          <path
            v-for="edge in activeEdges"
            :key="edge.id"
            :d="`M ${edge.startX} ${edge.startY} L ${edge.endX} ${edge.endY}`"
            class="edge-line"
          />
          <path v-if="pendingConnection" :d="pendingPath" class="edge-line pending" />
        </svg>

        <button
          v-for="badge in edgeBadges"
          :key="badge.id"
          type="button"
          class="edge-badge"
          :style="{ left: `${badge.left}px`, top: `${badge.top}px` }"
          @click="openEditDialog(activeTransitions.find(item => item.id === badge.id)!)"
        >
          {{ badge.label }}
        </button>

        <article
          v-for="status in activeStatuses"
          :key="status"
          class="status-node"
          data-testid="workflow-state-node"
          :data-status="status"
          :style="{ transform: `translate(${activeLayout[status]?.x || 0}px, ${activeLayout[status]?.y || 0}px)` }"
          @pointerup="finishConnection(status)"
        >
          <header class="node-header" @pointerdown.prevent="startNodeDrag($event, status)">
            <span class="node-title">{{ status }}</span>
            <button class="connect-handle" type="button" @pointerdown.prevent="startConnection($event, status)" />
          </header>

          <div class="node-body">
            <span class="node-meta">{{ activeTransitions.filter(item => item.currentStatus === status).length }} 条流出</span>
            <span class="node-meta">{{ activeTransitions.filter(item => item.nextStatus === status).length }} 条流入</span>
          </div>
        </article>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑流转规则' : '新增流转规则'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-form-item label="需求类型">
          <el-input :model-value="form.requirementType" disabled />
        </el-form-item>
        <div data-testid="workflow-from-status">
          <el-form-item label="源状态">
            <el-input :model-value="form.fromStatus" disabled />
          </el-form-item>
        </div>
        <div data-testid="workflow-to-status">
          <el-form-item label="目标状态">
            <el-input :model-value="form.toStatus" disabled />
          </el-form-item>
        </div>
        <div data-testid="workflow-condition-field">
          <el-form-item label="流转说明" prop="conditionType">
            <el-input v-model="form.conditionType" placeholder="例如：评估通过 / 进入设计阶段" />
          </el-form-item>
        </div>
        <div data-testid="workflow-roles-field">
          <el-form-item label="允许角色" prop="allowedRoles">
            <el-checkbox-group v-model="form.allowedRoles">
              <el-checkbox v-for="role in ROLE_OPTIONS" :key="role" :label="role">{{ role }}</el-checkbox>
            </el-checkbox-group>
          </el-form-item>
        </div>
        <el-form-item label="启用状态">
          <el-switch v-model="form.isActive" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button v-if="editingId" type="danger" plain @click="handleDeleteCurrent">删除</el-button>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.workflow-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.content-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.title-with-stats {
  display: flex;
  align-items: center;
  gap: 16px;
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--gray-800);
}

.inline-stats {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-left: 16px;
  border-left: 1px solid var(--gray-200);
}

.inline-stat {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.stat-num {
  font-size: 18px;
  font-weight: 700;
  color: var(--gray-800);
}

.stat-text {
  font-size: 13px;
  color: var(--gray-500);
}

.helper-copy {
  font-size: 13px;
  color: var(--gray-500);
}

.type-strip {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.type-chip {
  border: 1px solid var(--gray-200);
  border-radius: 999px;
  padding: 8px 14px;
  background: #fff;
  color: var(--gray-600);
  font-size: 13px;
  cursor: pointer;
}

.type-chip.active {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
}

.workflow-stage {
  background: #fff;
  border-radius: 20px;
  padding: 16px;
  box-shadow: var(--shadow-sm);
  overflow: auto;
}

.canvas-board {
  position: relative;
  border-radius: 20px;
  background:
    linear-gradient(90deg, rgba(148, 163, 184, 0.08) 1px, transparent 1px) 0 0 / 24px 24px,
    linear-gradient(rgba(148, 163, 184, 0.08) 1px, transparent 1px) 0 0 / 24px 24px,
    #f8fafc;
  border: 1px solid rgba(148, 163, 184, 0.18);
  overflow: hidden;
}

.canvas-lines {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.edge-line {
  stroke: rgba(79, 70, 229, 0.62);
  stroke-width: 2;
  fill: none;
}

.edge-line.pending {
  stroke-dasharray: 6 4;
}

.edge-badge {
  position: absolute;
  z-index: 2;
  min-width: 112px;
  border: 1px solid rgba(79, 70, 229, 0.18);
  border-radius: 999px;
  padding: 6px 10px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.08);
  color: var(--primary);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.status-node {
  position: absolute;
  width: 180px;
  min-height: 88px;
  border-radius: 18px;
  background: linear-gradient(135deg, #ffffff, #eef2ff);
  border: 1px solid rgba(79, 70, 229, 0.18);
  box-shadow: 0 18px 36px rgba(15, 23, 42, 0.08);
  user-select: none;
}

.node-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 14px 14px 10px;
  cursor: grab;
}

.node-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--gray-800);
}

.connect-handle {
  width: 16px;
  height: 16px;
  border: none;
  border-radius: 999px;
  background: var(--primary);
  box-shadow: 0 0 0 4px rgba(79, 70, 229, 0.14);
  cursor: crosshair;
  flex-shrink: 0;
}

.node-body {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 0 14px 14px;
}

.node-meta {
  font-size: 11px;
  color: var(--gray-500);
}

@media (max-width: 900px) {
  .content-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .title-with-stats {
    flex-direction: column;
    align-items: flex-start;
  }

  .inline-stats {
    padding-left: 0;
    border-left: none;
  }
}
</style>
