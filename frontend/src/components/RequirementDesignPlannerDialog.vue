<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'

interface DesignPlanDraft {
  workType: string
  label: string
  selected: boolean
  designerId?: number
  estimatedHours?: number | null
  plannedCompletedAt?: string
}

const DESIGN_OPTIONS = [
  { workType: '原型设计', label: '原型设计' },
  { workType: 'UI设计', label: 'UI设计' },
  { workType: '技术方案设计', label: '技术方案' }
]

const props = defineProps<{
  modelValue: boolean
  users: Array<{ id: number; realName?: string; username?: string }>
  plans?: Array<{
    workType: string
    designerId?: number
    estimatedHours?: number
    plannedCompletedAt?: string
  }>
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'save', value: Array<{
    workType: string
    designerId: number
    estimatedHours?: number
    plannedCompletedAt?: string
  }>): void
}>()

const form = reactive<Record<string, DesignPlanDraft>>({})

const resetForm = () => {
  for (const option of DESIGN_OPTIONS) {
    const matched = props.plans?.find(plan => plan.workType === option.workType)
    form[option.workType] = {
      workType: option.workType,
      label: option.label,
      selected: Boolean(matched),
      designerId: matched?.designerId,
      estimatedHours: matched?.estimatedHours ?? null,
      plannedCompletedAt: matched?.plannedCompletedAt?.slice?.(0, 10) || ''
    }
  }
}

watch(
  () => [props.modelValue, props.plans],
  () => {
    if (props.modelValue) {
      resetForm()
    }
  },
  { immediate: true, deep: true }
)

const options = computed(() => DESIGN_OPTIONS.map(option => form[option.workType]))

const close = () => emit('update:modelValue', false)

const submit = () => {
  const selected = options.value.filter(option => option.selected)
  if (!selected.length) {
    ElMessage.warning('请至少选择一个设计环节')
    return
  }

  for (const item of selected) {
    if (!item.designerId) {
      ElMessage.warning(`请为${item.label}选择负责人`)
      return
    }
    if (!item.estimatedHours) {
      ElMessage.warning(`请填写${item.label}的预估工作量`)
      return
    }
    if (!item.plannedCompletedAt) {
      ElMessage.warning(`请填写${item.label}的计划完成时间`)
      return
    }
  }

  emit('save', selected.map(item => ({
    workType: item.workType,
    designerId: item.designerId!,
    estimatedHours: Number(item.estimatedHours),
    plannedCompletedAt: item.plannedCompletedAt
  })))
}
</script>

<template>
  <div v-if="modelValue" class="modal-overlay" @click.self="close">
    <div class="modal planner-modal">
      <div class="modal-header">
        <h3 class="modal-title">配置设计规划</h3>
        <button class="modal-close" @click="close">×</button>
      </div>
      <div class="modal-body">
        <div v-for="option in options" :key="option.workType" class="plan-card">
          <label class="plan-toggle">
            <input v-model="option.selected" type="checkbox">
            <span>{{ option.label }}</span>
          </label>

          <div v-if="option.selected" class="plan-grid">
            <label class="plan-field">
              <span>负责人</span>
              <select v-model="option.designerId" class="form-input">
                <option :value="undefined">请选择</option>
                <option v-for="user in users" :key="user.id" :value="user.id">
                  {{ user.realName || user.username || user.id }}
                </option>
              </select>
            </label>

            <label class="plan-field">
              <span>预估工作量</span>
              <input v-model="option.estimatedHours" type="number" min="1" class="form-input" placeholder="小时">
            </label>

            <label class="plan-field">
              <span>计划完成时间</span>
              <input v-model="option.plannedCompletedAt" type="date" class="form-input">
            </label>
          </div>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-default" @click="close">取消</button>
        <button class="btn btn-primary" @click="submit">保存规划</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.planner-modal {
  width: min(720px, 92vw);
}

.plan-card {
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-md);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.plan-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--gray-800);
}

.plan-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.plan-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  color: var(--gray-600);
}

@media (max-width: 768px) {
  .plan-grid {
    grid-template-columns: 1fr;
  }
}
</style>
