<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { TableInstance, TabPaneName } from 'element-plus'
import { Check, Close, Key, Refresh, Select } from '@element-plus/icons-vue'
import { ApiRequestError, api } from '@/utils/api'
import type {
  SeeyonOaAffair,
  SeeyonOaApproveAction,
  SeeyonOaBatchApproveItem,
  SeeyonOaSessionStatus
} from '@/types/oa-affairs'

type AffairTab = 'pending' | 'done'

/** 会话失效/未授权的服务端提示都落在 400 上，命中即弹授权入口。 */
const SESSION_PROMPT_PATTERN = /重新授权|重新粘贴|未授权|JSESSIONID/i

const activeTab = ref<AffairTab>('pending')

const session = ref<SeeyonOaSessionStatus>({ authorized: false, hint: '' })
const sessionLoading = ref(false)
const sessionError = ref('')
const authPrompt = ref('')
const clearingSession = ref(false)

const authDialogVisible = ref(false)
const authCookie = ref('')
const authError = ref('')
const authorizing = ref(false)

const pendingRows = ref<SeeyonOaAffair[]>([])
const doneRows = ref<SeeyonOaAffair[]>([])
const listLoading = ref(false)
const listLoaded = ref(false)
const loadError = ref('')
const selectedRows = ref<SeeyonOaAffair[]>([])
const actingIds = ref<Set<string>>(new Set())
const tableRef = ref<TableInstance>()

const batchRunning = ref(false)
const batchAction = ref<SeeyonOaApproveAction>('approve')
const batchResults = ref<SeeyonOaBatchApproveItem[]>([])
const batchSubjects = ref<Record<string, string>>({})
const batchResultVisible = ref(false)

const allSelected = computed(() =>
  pendingRows.value.length > 0 && selectedRows.value.length === pendingRows.value.length
)
const batchSuccessCount = computed(() => batchResults.value.filter(item => item.success).length)
const batchFailureCount = computed(() => batchResults.value.length - batchSuccessCount.value)

const errorText = (error: unknown, fallback: string) =>
  error instanceof Error && error.message ? error.message : fallback

/** 会话失效/未授权的服务端提示都落在 400 上；命中则刷新授权状态并弹出授权入口。 */
const handleSessionFailure = (error: unknown, message: string) => {
  if (!(error instanceof ApiRequestError) || error.status !== 400 || !SESSION_PROMPT_PATTERN.test(message)) return false
  authPrompt.value = message
  void loadSession()
  openAuthDialog(message)
  return true
}

const formatDateTime = (value?: string) => (value ? value.replace('T', ' ').slice(0, 16) : '—')
const affairType = (row: SeeyonOaAffair) => row.appName || row.state || ''

const loadSession = async () => {
  sessionLoading.value = true
  sessionError.value = ''
  try {
    session.value = await api.getOaSessionStatus()
  } catch (error: unknown) {
    sessionError.value = errorText(error, '授权状态获取失败')
  } finally {
    sessionLoading.value = false
  }
}

const loadAffairs = async () => {
  listLoading.value = true
  loadError.value = ''
  try {
    if (activeTab.value === 'pending') {
      pendingRows.value = await api.getOaPendingAffairs()
      tableRef.value?.clearSelection()
    } else {
      doneRows.value = await api.getOaDoneAffairs()
    }
    listLoaded.value = true
    authPrompt.value = ''
  } catch (error: unknown) {
    if (activeTab.value === 'pending') pendingRows.value = []
    else doneRows.value = []
    listLoaded.value = false
    loadError.value = errorText(error, '事项加载失败')
    if (!handleSessionFailure(error, loadError.value)) ElMessage.error(loadError.value)
  } finally {
    listLoading.value = false
  }
}

const onTabChange = (name: TabPaneName) => {
  activeTab.value = name === 'done' ? 'done' : 'pending'
  selectedRows.value = []
  void loadAffairs()
}

const openAuthDialog = (reason = '') => {
  authCookie.value = ''
  authError.value = reason
  authDialogVisible.value = true
}

const switchSelectAll = () => {
  if (allSelected.value) tableRef.value?.clearSelection()
  else tableRef.value?.toggleAllSelection()
}

const submitAuthorization = async () => {
  const cookie = authCookie.value.trim()
  if (!cookie) {
    authError.value = '请粘贴浏览器中的 JSESSIONID 值'
    return
  }
  authorizing.value = true
  authError.value = ''
  try {
    session.value = await api.authorizeOaSession(cookie)
    authCookie.value = ''
    authPrompt.value = ''
    authDialogVisible.value = false
    ElMessage.success(session.value.hint || '授权成功')
    await loadAffairs()
  } catch (error: unknown) {
    authError.value = errorText(error, '授权失败，请检查 JSESSIONID 是否有效')
  } finally {
    authorizing.value = false
  }
}

const clearAuthorization = async () => {
  try {
    await ElMessageBox.confirm('清除后待办取数与审批都需要重新粘贴 JSESSIONID 授权。', '清除 OA 授权', {
      confirmButtonText: '清除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  clearingSession.value = true
  try {
    await api.clearOaSession()
    await loadSession()
    ElMessage.success('OA 网页会话授权已清除')
  } catch (error: unknown) {
    ElMessage.error(errorText(error, '清除授权失败'))
  } finally {
    clearingSession.value = false
  }
}

const approve = async (row: SeeyonOaAffair, action: SeeyonOaApproveAction) => {
  const label = action === 'approve' ? '同意' : '驳回'
  try {
    await ElMessageBox.confirm(`确定${label}「${row.subject || row.id}」吗？`, `${label}事项`, {
      confirmButtonText: label,
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  actingIds.value.add(row.id)
  try {
    const result = await api.approveOaAffair(row.id, action)
    ElMessage.success(result || `${label}成功`)
    await loadAffairs()
  } catch (error: unknown) {
    const message = errorText(error, `${label}失败`)
    if (!handleSessionFailure(error, message)) ElMessage.error(message)
  } finally {
    actingIds.value.delete(row.id)
  }
}

const runBatch = async (action: SeeyonOaApproveAction) => {
  const ids = selectedRows.value.map(row => row.id)
  if (ids.length === 0) {
    ElMessage.warning('请先勾选要审批的待办事项')
    return
  }
  const label = action === 'approve' ? '同意' : '驳回'
  try {
    await ElMessageBox.confirm(
      `将对已勾选的 ${ids.length} 项执行「${label}」，逐项执行并展示每项结果。确定继续吗？`,
      `批量${label}`,
      { confirmButtonText: `批量${label}`, cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  batchSubjects.value = Object.fromEntries(selectedRows.value.map(row => [row.id, row.subject || row.id]))
  batchRunning.value = true
  batchAction.value = action
  try {
    batchResults.value = await api.batchApproveOaAffairs(ids, action)
    batchResultVisible.value = true
    const failed = batchResults.value.filter(item => !item.success).length
    if (failed === 0) ElMessage.success(`批量${label}完成：${batchResults.value.length} 项全部成功`)
    else ElMessage.warning(`批量${label}完成：成功 ${batchResults.value.length - failed} 项，失败 ${failed} 项`)
    await loadAffairs()
  } catch (error: unknown) {
    const message = errorText(error, `批量${label}失败`)
    if (!handleSessionFailure(error, message)) ElMessage.error(message)
  } finally {
    batchRunning.value = false
  }
}

onMounted(async () => {
  await loadSession()
  await loadAffairs()
})
</script>

<template>
  <div class="oa-affairs-page">
    <header class="page-header">
      <div>
        <h1>OA 待办</h1>
        <p>致远 OA 待办 / 已办与批量审批；REST 接口被网关拦截时由服务端自动走网页会话通道。</p>
      </div>
      <el-button :icon="Refresh" :loading="listLoading" @click="loadAffairs">刷新</el-button>
    </header>

    <section class="session-card" :class="{ unauthorized: !session.authorized || authPrompt }" aria-label="OA 网页会话授权状态">
      <div class="session-main">
        <el-icon class="session-icon"><Key /></el-icon>
        <div class="session-text">
          <div class="session-title">
            <span>网页会话授权</span>
            <el-tag :type="session.authorized && !authPrompt ? 'success' : 'warning'" effect="plain" size="small">
              {{ authPrompt ? '需重新授权' : session.authorized ? '已授权' : '未授权' }}
            </el-tag>
            <el-tag v-if="sessionLoading" type="info" effect="plain" size="small">检测中…</el-tag>
          </div>
          <p class="session-hint">{{ sessionError || authPrompt || session.hint || '正在获取授权状态…' }}</p>
        </div>
      </div>
      <div class="session-actions">
        <el-button type="primary" :icon="Key" @click="openAuthDialog()">
          {{ session.authorized ? '重新授权' : '去授权' }}
        </el-button>
        <el-button v-if="session.authorized" :loading="clearingSession" @click="clearAuthorization">清除授权</el-button>
      </div>
    </section>

    <el-tabs v-model="activeTab" class="affair-tabs" @tab-change="onTabChange">
      <el-tab-pane label="待办" name="pending">
        <div class="toolbar">
          <el-button :icon="Refresh" :loading="listLoading" @click="loadAffairs">刷新</el-button>
          <el-button :icon="Select" :disabled="pendingRows.length === 0" @click="switchSelectAll">
            {{ allSelected ? '取消全选' : '全选' }}
          </el-button>
          <span class="selection-hint">已选 {{ selectedRows.length }} / {{ pendingRows.length }} 项</span>
          <span class="toolbar-gap" />
          <el-button
            type="success"
            :icon="Check"
            :disabled="!session.authorized"
            :loading="batchRunning && batchAction === 'approve'"
            @click="runBatch('approve')"
          >
            批量同意
          </el-button>
          <el-button
            type="danger"
            :icon="Close"
            :disabled="!session.authorized"
            :loading="batchRunning && batchAction === 'reject'"
            @click="runBatch('reject')"
          >
            批量驳回
          </el-button>
        </div>

        <el-alert
          v-if="loadError"
          class="list-error"
          type="warning"
          :closable="false"
          show-icon
          :title="loadError"
        >
          <template #default>
            <el-button link type="primary" @click="openAuthDialog(loadError)">去授权</el-button>
          </template>
        </el-alert>

        <el-table
          ref="tableRef"
          v-loading="listLoading"
          :data="pendingRows"
          row-key="id"
          class="affair-table"
          @selection-change="selectedRows = $event"
        >
          <el-table-column type="selection" width="46" />
          <el-table-column label="标题" min-width="280">
            <template #default="{ row }">
              <div class="subject-cell">
                <span class="subject-text">{{ row.subject || '（无标题）' }}</span>
                <el-link
                  v-if="row.linkUrl"
                  class="subject-link"
                  type="primary"
                  :underline="false"
                  :href="row.linkUrl"
                  target="_blank"
                  rel="noopener"
                >
                  详情
                </el-link>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="发起人" width="140">
            <template #default="{ row }">{{ row.senderName || '—' }}</template>
          </el-table-column>
          <el-table-column label="接收时间" width="170">
            <template #default="{ row }">
              <span class="time-cell">{{ formatDateTime(row.createDate) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="140">
            <template #default="{ row }">
              <el-tag v-if="affairType(row)" size="small" effect="plain">{{ affairType(row) }}</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                type="success"
                :icon="Check"
                :disabled="!session.authorized || actingIds.has(row.id)"
                @click="approve(row, 'approve')"
              >
                同意
              </el-button>
              <el-button
                link
                type="danger"
                :icon="Close"
                :disabled="!session.authorized || actingIds.has(row.id)"
                @click="approve(row, 'reject')"
              >
                驳回
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-guide">
              <p v-if="!session.authorized">尚未完成 OA 网页会话授权，无法获取待办（REST 通道被拦截时必填）。</p>
              <p v-else-if="listLoaded">当前没有待办事项。</p>
              <p v-else>待办尚未加载，可点击「刷新」重试。</p>
              <el-button v-if="!session.authorized" type="primary" size="small" @click="openAuthDialog()">去授权</el-button>
            </div>
          </template>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="已办" name="done">
        <div class="toolbar">
          <el-button :icon="Refresh" :loading="listLoading" @click="loadAffairs">刷新</el-button>
          <span class="toolbar-note">已办事项仅供查阅，审批动作请在「待办」页签执行。</span>
        </div>
        <el-table v-loading="listLoading" :data="doneRows" row-key="id" class="affair-table">
          <el-table-column label="标题" min-width="280">
            <template #default="{ row }">
              <div class="subject-cell">
                <span class="subject-text">{{ row.subject || '（无标题）' }}</span>
                <el-link
                  v-if="row.linkUrl"
                  class="subject-link"
                  type="primary"
                  :underline="false"
                  :href="row.linkUrl"
                  target="_blank"
                  rel="noopener"
                >
                  详情
                </el-link>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="发起人" width="140">
            <template #default="{ row }">{{ row.senderName || '—' }}</template>
          </el-table-column>
          <el-table-column label="接收时间" width="170">
            <template #default="{ row }">
              <span class="time-cell">{{ formatDateTime(row.createDate) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="140">
            <template #default="{ row }">
              <el-tag v-if="affairType(row)" size="small" effect="plain">{{ affairType(row) }}</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-guide">
              <p v-if="!session.authorized">尚未完成 OA 网页会话授权，无法获取已办。</p>
              <p v-else-if="listLoaded">暂无已办事项。</p>
              <p v-else>已办尚未加载，可点击「刷新」重试。</p>
              <el-button v-if="!session.authorized" type="primary" size="small" @click="openAuthDialog()">去授权</el-button>
            </div>
          </template>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="authDialogVisible" title="OA 网页会话授权" width="560px">
      <el-alert
        class="auth-error"
        type="warning"
        :closable="false"
        show-icon
        :title="authError || 'REST 接口被致远网关拦截时，待办取数与审批通过网页会话通道完成。'"
      />
      <ol class="auth-steps">
        <li>浏览器登录致远 OA 并进入工作台（与管理员日常审批同一浏览器）。</li>
        <li>打开开发者工具 → Application（应用）→ Cookies → 复制 <code>JSESSIONID</code> 的值。</li>
        <li>粘贴到下方保存，服务端复用该会话取数与审批；失效时按提示重新粘贴。</li>
      </ol>
      <el-input
        v-model="authCookie"
        type="textarea"
        :rows="3"
        placeholder="粘贴 JSESSIONID 的值（可含 JSESSIONID= 前缀）"
        @keyup.enter.exact.prevent="submitAuthorization"
      />
      <p class="auth-note">JSESSIONID 等同登录凭据，仅保存在服务端连接器加密配置中。</p>
      <template #footer>
        <el-button @click="authDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="authorizing" @click="submitAuthorization">保存授权</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="batchResultVisible"
      :title="`批量${batchAction === 'approve' ? '同意' : '驳回'}结果`"
      width="640px"
    >
      <p class="batch-summary">
        共 {{ batchResults.length }} 项：成功 {{ batchSuccessCount }} 项，失败 {{ batchFailureCount }} 项。
      </p>
      <el-table :data="batchResults" max-height="360" class="affair-table">
        <el-table-column label="事项" min-width="240">
          <template #default="{ row }">{{ batchSubjects[row.affairId] || row.affairId }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" size="small" effect="plain">
              {{ row.success ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="返回信息" min-width="240">
          <template #default="{ row }">
            <span class="result-text">{{ row.result || '—' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button type="primary" @click="batchResultVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.oa-affairs-page { padding: 24px; min-width: 0; color: #252a31; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.page-header h1 { margin: 0; font-size: 24px; letter-spacing: 0; }
.page-header p { margin: 5px 0 0; color: #757d88; font-size: 13px; }
.session-card { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 16px; margin-bottom: 16px; border: 1px solid #cfe4d7; border-radius: 6px; background: #f3faf6; }
.session-card.unauthorized { border-color: #f0dcb4; background: #fffaf0; }
.session-main { display: flex; align-items: flex-start; gap: 12px; min-width: 0; }
.session-icon { margin-top: 2px; font-size: 20px; color: #3a67b7; }
.session-text { min-width: 0; }
.session-title { display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: 14px; }
.session-hint { margin: 4px 0 0; color: #5b636e; font-size: 13px; overflow-wrap: anywhere; }
.session-actions { display: flex; gap: 8px; flex-shrink: 0; }
.affair-tabs { min-width: 0; }
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.toolbar-gap { flex: 1; }
.toolbar-note { color: #7a828d; font-size: 12px; }
.selection-hint { color: #5b636e; font-size: 13px; font-variant-numeric: tabular-nums; }
.list-error { margin-bottom: 12px; }
.affair-table { width: 100%; border: 1px solid #e3e6ea; border-radius: 6px; overflow: hidden; }
.subject-cell { display: flex; align-items: center; gap: 8px; min-width: 0; }
.subject-text { font-weight: 600; color: #282d34; overflow-wrap: anywhere; }
.subject-link { flex-shrink: 0; }
.time-cell { color: #5b636e; font-size: 12px; font-variant-numeric: tabular-nums; }
.result-text { color: #555d68; font-size: 13px; overflow-wrap: anywhere; }
.batch-summary { margin: 0 0 12px; color: #3f4650; font-size: 13px; }
.empty-guide { padding: 18px 0; color: #757d88; font-size: 13px; }
.empty-guide p { margin: 0 0 10px; }
.auth-error { margin-bottom: 14px; }
.auth-steps { margin: 0 0 14px; padding-left: 20px; color: #555d68; font-size: 13px; line-height: 1.9; }
.auth-steps code { padding: 1px 4px; border-radius: 3px; background: #eef0f3; font-size: 12px; }
.auth-note { margin: 10px 0 0; color: #8a919b; font-size: 12px; }
@media (max-width: 768px) {
  .oa-affairs-page { padding: 16px; }
  .session-card { flex-direction: column; align-items: stretch; }
  .session-actions { justify-content: flex-end; }
}
</style>
