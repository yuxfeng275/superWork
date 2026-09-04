<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Connection, Refresh, Setting } from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import type { SystemConfigGroup, SystemConfigGroupSummary, SystemConfigItem } from '@/types/system-config'

const groups = ref<SystemConfigGroupSummary[]>([])
const currentGroup = ref<SystemConfigGroup>()
const selectedGroupCode = ref('')
const values = reactive<Record<string, string>>({})
const loading = ref(true)
const saving = ref(false)
const testing = ref('')
const error = ref('')

const configuredText = computed(() => currentGroup.value
  ? `${currentGroup.value.items.filter(item => item.configured).length}/${currentGroup.value.items.length} 项已配置`
  : '')

async function loadGroups() {
  loading.value = true
  error.value = ''
  try {
    groups.value = await api.getSystemConfigGroups()
    const code = selectedGroupCode.value || groups.value[0]?.groupCode
    if (code) await selectGroup(code)
  } catch (err: unknown) {
    error.value = errorText(err, '配置列表加载失败')
  } finally {
    loading.value = false
  }
}

async function selectGroup(groupCode: string) {
  selectedGroupCode.value = groupCode
  error.value = ''
  try {
    currentGroup.value = await api.getSystemConfigGroup(groupCode)
    Object.keys(values).forEach(key => delete values[key])
    currentGroup.value.items.forEach(item => {
      values[item.key] = item.sensitive ? '' : item.value || ''
    })
  } catch (err: unknown) {
    error.value = errorText(err, '配置组加载失败')
  }
}

async function saveGroup() {
  if (!currentGroup.value) return
  saving.value = true
  try {
    currentGroup.value = await api.saveSystemConfigGroup(currentGroup.value.groupCode, { ...values })
    currentGroup.value.items.forEach(item => {
      if (item.sensitive) values[item.key] = ''
      else values[item.key] = item.value || ''
    })
    await refreshGroupSummary()
    ElMessage.success('系统配置已保存')
  } catch (err: unknown) {
    currentGroup.value.items.filter(item => item.sensitive).forEach(item => { values[item.key] = '' })
    ElMessage.error(errorText(err, '系统配置保存失败'))
  } finally {
    saving.value = false
  }
}

async function testIntegration(integration: 'deepseek' | 'wecom' | 'worktime' | 'yuque') {
  if (!currentGroup.value) return
  testing.value = integration
  try {
    const result = await api.testSystemConfigIntegration(currentGroup.value.groupCode, integration)
    result.success ? ElMessage.success(result.message) : ElMessage.error(result.message)
  } catch (err: unknown) {
    ElMessage.error(errorText(err, '连接测试失败'))
  } finally {
    testing.value = ''
  }
}

async function refreshGroupSummary() {
  groups.value = await api.getSystemConfigGroups()
}

const CREDENTIAL_KEYS: Record<'deepseek' | 'wecom' | 'worktime' | 'yuque', string> = {
  deepseek: 'api-key',
  wecom: 'secret',
  worktime: 'password',
  yuque: 'token'
}

function integrationConfigured(prefix: 'deepseek' | 'wecom' | 'worktime' | 'yuque') {
  const enabled = values[`${prefix}.enabled`] === 'true'
  const credential = currentGroup.value?.items.find(item => item.key === `${prefix}.${CREDENTIAL_KEYS[prefix]}`)
  return enabled && credential?.configured
}

function inputType(item: SystemConfigItem) {
  return item.sensitive ? 'password' : item.valueType === 'NUMBER' ? 'number' : 'text'
}

function placeholder(item: SystemConfigItem) {
  if (item.sensitive && item.configured) return '已配置；留空保持不变'
  return item.sensitive ? `请输入${item.name}` : item.description || `请输入${item.name}`
}

function errorText(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

onMounted(loadGroups)
</script>

<template>
  <div class="config-page" v-loading="loading">
    <header class="page-head">
      <div><span class="eyebrow">SYSTEM SETTINGS</span><h2>配置管理</h2><p>统一管理各系统集成、服务地址、功能开关和敏感凭据。</p></div>
      <el-button :icon="Refresh" @click="loadGroups">刷新</el-button>
    </header>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />

    <div class="config-layout">
      <aside class="group-list">
        <button v-for="group in groups" :key="group.groupCode" type="button" class="group-button" :class="{ active: selectedGroupCode === group.groupCode }" @click="selectGroup(group.groupCode)">
          <el-icon><Setting /></el-icon>
          <span><strong>{{ group.groupName }}</strong><small>{{ group.configuredCount }}/{{ group.itemCount }} 项已配置</small></span>
        </button>
        <el-empty v-if="!groups.length" description="暂无配置组" :image-size="70" />
      </aside>

      <main v-if="currentGroup" class="config-editor">
        <div class="editor-head"><div><h3>{{ currentGroup.groupName }}</h3><p>{{ currentGroup.description }}</p></div><el-tag type="info">{{ configuredText }}</el-tag></div>

        <el-form label-position="top" class="config-form">
          <el-form-item v-for="item in currentGroup.items" :key="item.key" :label="item.name" :required="item.required">
            <el-switch v-if="item.valueType === 'BOOLEAN'" :model-value="values[item.key] === 'true'" @update:model-value="(value: boolean | string | number) => values[item.key] = String(value)" />
            <el-input v-else v-model="values[item.key]" :type="inputType(item)" :show-password="item.sensitive" :autocomplete="item.sensitive ? 'new-password' : 'off'" :placeholder="placeholder(item)" :aria-label="item.name" />
            <span class="field-help">{{ item.description }}<template v-if="item.sensitive"> · 保存后不回显</template></span>
          </el-form-item>
        </el-form>

        <section v-if="currentGroup.groupCode === 'email-integration'" class="connection-tests">
          <div><h4>连接测试</h4><p>请先保存配置，再验证外部服务是否可用。</p></div>
          <div class="test-actions">
            <el-button :icon="Connection" :loading="testing === 'deepseek'" :disabled="!integrationConfigured('deepseek')" @click="testIntegration('deepseek')">测试 DeepSeek</el-button>
            <el-button :icon="Connection" :loading="testing === 'wecom'" :disabled="!integrationConfigured('wecom')" @click="testIntegration('wecom')">测试企业微信</el-button>
          </div>
        </section>

        <section v-if="currentGroup.groupCode === 'ai-connector'" class="connection-tests">
          <div><h4>连接测试</h4><p>请先保存配置，再验证外部服务是否可用。</p></div>
          <div class="test-actions">
            <el-button :icon="Connection" :loading="testing === 'worktime'" :disabled="!integrationConfigured('worktime')" @click="testIntegration('worktime')">测试工时系统</el-button>
            <el-button :icon="Connection" :loading="testing === 'yuque'" :disabled="!integrationConfigured('yuque')" @click="testIntegration('yuque')">测试语雀</el-button>
          </div>
        </section>

        <footer class="editor-footer"><el-button type="primary" size="large" :loading="saving" @click="saveGroup">保存配置</el-button></footer>
      </main>
    </div>
  </div>
</template>

<style scoped>
.config-page { display: flex; flex-direction: column; gap: 18px; min-width: 0; text-align: left; }
.page-head, .config-layout { background: #fff; border: 1px solid var(--gray-200); border-radius: 16px; box-shadow: 0 8px 24px rgba(15, 23, 42, .04); }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 22px 24px; }
.page-head h2 { margin: 4px 0 6px; color: var(--gray-900); font-size: 22px; }.page-head p { margin: 0; color: var(--gray-500); }.eyebrow { color: var(--primary); font-size: 11px; font-weight: 700; letter-spacing: .1em; }
.config-layout { display: grid; grid-template-columns: 250px minmax(0, 1fr); min-height: 560px; overflow: hidden; }
.group-list { padding: 14px; border-right: 1px solid var(--gray-200); background: var(--gray-50); }
.group-button { display: flex; align-items: center; gap: 11px; width: 100%; padding: 13px; border: 0; border-radius: 10px; background: transparent; color: var(--gray-600); text-align: left; cursor: pointer; }.group-button:hover { background: #fff; }.group-button.active { background: var(--primary-light); color: var(--primary); }.group-button span { display: flex; flex-direction: column; gap: 3px; min-width: 0; }.group-button strong { font-size: 14px; }.group-button small { color: var(--gray-500); font-size: 11px; }
.config-editor { min-width: 0; padding: 26px; }.editor-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding-bottom: 18px; border-bottom: 1px solid var(--gray-200); }.editor-head h3 { margin: 0 0 6px; color: var(--gray-900); font-size: 20px; }.editor-head p { margin: 0; color: var(--gray-500); }
.config-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; padding-top: 22px; }.field-help { display: block; margin-top: 5px; color: var(--gray-500); font-size: 12px; line-height: 1.45; }
.connection-tests { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-top: 8px; padding: 16px; border: 1px solid #dbeafe; border-radius: 12px; background: #f8fbff; }.connection-tests h4 { margin: 0 0 4px; color: var(--gray-800); }.connection-tests p { margin: 0; color: var(--gray-500); font-size: 12px; }.test-actions { display: flex; gap: 8px; flex-shrink: 0; }
.editor-footer { display: flex; justify-content: flex-end; margin-top: 22px; padding-top: 18px; border-top: 1px solid var(--gray-200); }
@media (max-width: 820px) { .config-layout { grid-template-columns: 1fr; }.group-list { display: flex; overflow-x: auto; border-right: 0; border-bottom: 1px solid var(--gray-200); }.group-button { min-width: 210px; }.config-form { grid-template-columns: 1fr; }.connection-tests { align-items: flex-start; flex-direction: column; }.test-actions { width: 100%; flex-direction: column; }.test-actions .el-button { width: 100%; margin-left: 0; } }
@media (max-width: 480px) { .page-head, .editor-head { align-items: flex-start; flex-direction: column; }.config-editor { padding: 18px; }.editor-footer .el-button { width: 100%; } }
</style>
