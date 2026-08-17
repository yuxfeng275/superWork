<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, ArrowRight, Calendar, Close, CopyDocument, Delete, Document, Edit, Lock, Paperclip, Refresh, Search } from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import type {
  EmailAccount,
  EmailDailyDigest,
  EmailDigestItem,
  EmailMessageDetail,
  EmailMessagePage,
  EmailMessageSummary,
  EmailSyncStatus,
  EmailWeComMapping
} from '@/types/email'

const pageSize = 20
const account = ref<EmailAccount>()
const accountLoading = ref(true)
const accountError = ref('')
const savingAccount = ref(false)
const testingConnection = ref(false)
const settingsVisible = ref(false)
const bindForm = reactive({ emailAddress: '', appPassword: '' })
const weComMapping = ref<EmailWeComMapping>()
const weComForm = reactive({ userId: '', enabled: true })
const savingWeCom = ref(false)
const selectedDate = ref(shanghaiDate(new Date(Date.now() - 86_400_000)))
const digest = ref<EmailDailyDigest>()
const digestLoading = ref(false)
const digestError = ref('')
const regeneratingDigest = ref(false)

const messages = ref<EmailMessagePage>(emptyMessagePage())
const messagesLoading = ref(false)
const messagesError = ref('')
const inboxFilters = reactive({ date: '', keyword: '', page: 1 })

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detailActiveTab = ref('original')
const digestActiveTab = ref('overview')
const interpreting = ref(false)
const selectedMessage = ref<EmailMessageDetail>()
const detailMessageId = ref<number>()

const syncStatus = ref<EmailSyncStatus>({ status: 'IDLE' })
let syncPollTimer: number | undefined

const configured = computed(() => Boolean(account.value?.configured))
const syncRunning = computed(() => ['QUEUED', 'RUNNING'].includes(syncStatus.value.status))
const maskedAddress = computed(() => maskEmail(account.value?.emailAddress))
const accountStatusText = computed(() => {
  if (syncRunning.value) return '正在同步'
  if (syncStatus.value.status === 'SUCCESS') return '同步成功'
  if (syncStatus.value.status === 'FAILED') return '同步失败'
  return account.value?.connectionStatus === 'CONNECTED' ? '连接正常' : '等待同步'
})
const syncMessage = computed(() => syncStatus.value.message || syncStatus.value.error || '')
const digestModeLabel = computed(() => {
  if (digest.value?.status === 'EMPTY') return '空摘要'
  if (digest.value?.status === 'PENDING') return '待生成'
  if (digest.value?.status === 'DEGRADED' || digest.value?.generationMode === 'RULES') return '规则降级'
  if (digest.value?.generationMode === 'AI') return 'AI'
  return digest.value?.status === 'FAILED' ? '生成失败' : ''
})
const digestModeType = computed(() => {
  if (digest.value?.status === 'DEGRADED' || digest.value?.generationMode === 'RULES') return 'warning'
  if (digest.value?.status === 'FAILED') return 'danger'
  if (digest.value?.status === 'EMPTY' || digest.value?.status === 'PENDING') return 'info'
  return 'success'
})
const pushLabel = computed(() => {
  const labels: Record<string, string> = {
    SUCCESS: '已推送',
    PENDING: '待推送',
    NOT_CONFIGURED: '未配置',
    UNMAPPED: '未映射',
    FAILED: '失败'
  }
  return digest.value?.pushStatus ? labels[digest.value.pushStatus] || digest.value.pushStatus : ''
})

const selectedMessageIndex = computed(() => {
  if (!selectedMessage.value) return -1
  return messages.value.records.findIndex(message => message.id === selectedMessage.value?.id)
})
const selectedMessagePosition = computed(() => selectedMessageIndex.value >= 0
  ? `${selectedMessageIndex.value + 1} / ${messages.value.records.length}`
  : '')
const previousMessage = computed(() => selectedMessageIndex.value > 0
  ? messages.value.records[selectedMessageIndex.value - 1]
  : undefined)
const nextMessage = computed(() => selectedMessageIndex.value >= 0
  && selectedMessageIndex.value < messages.value.records.length - 1
  ? messages.value.records[selectedMessageIndex.value + 1]
  : undefined)
const senderInitial = computed(() => {
  const source = selectedMessage.value?.fromName || selectedMessage.value?.fromAddress || '邮'
  return source.trim().charAt(0).toUpperCase() || '邮'
})

function emptyMessagePage(): EmailMessagePage {
  return { records: [], total: 0, size: pageSize, current: 1, pages: 0 }
}

function shanghaiDate(value: Date) {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).format(value)
}

function shiftDate(value: string, days: number) {
  const date = new Date(`${value}T12:00:00+08:00`)
  date.setUTCDate(date.getUTCDate() + days)
  return shanghaiDate(date)
}

function maskEmail(value?: string) {
  if (!value) return '邮箱账户'
  const [local, domain] = value.split('@')
  if (!domain) return '***'
  if (local.length <= 2) return `${local.charAt(0)}***@${domain}`
  return `${local.charAt(0)}***${local.charAt(local.length - 1)}@${domain}`
}

function formatDateTime(value?: string) {
  if (!value) return '暂无记录'
  return value.replace('T', ' ').slice(0, 16)
}

function formatFullDateTime(value?: string) {
  if (!value) return '时间未知'
  const hasTimezone = /(?:Z|[+-]\d{2}:?\d{2})$/.test(value)
  const date = new Date(value.includes('T') && !hasTimezone ? `${value}+08:00` : value)
  if (Number.isNaN(date.getTime())) return value.replace('T', ' ')
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric', month: 'long', day: 'numeric', weekday: 'short',
    hour: '2-digit', minute: '2-digit'
  }).format(date)
}

function attachmentIcon(contentType?: string) {
  if (contentType?.includes('pdf')) return 'PDF'
  if (contentType?.startsWith('image/')) return 'IMG'
  if (contentType?.includes('sheet') || contentType?.includes('excel')) return 'XLS'
  if (contentType?.includes('word') || contentType?.includes('document')) return 'DOC'
  if (contentType?.includes('zip') || contentType?.includes('compressed')) return 'ZIP'
  return 'FILE'
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function errorText(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

async function loadAccount() {
  accountLoading.value = true
  accountError.value = ''
  try {
    account.value = await api.getEmailAccount()
    if (account.value.configured) {
      bindForm.emailAddress = account.value.emailAddress || ''
      await Promise.all([loadDigest(), loadMessages(), loadSyncStatus(false)])
      void loadWeComMapping()
    }
  } catch (error: unknown) {
    accountError.value = errorText(error, '邮箱账户加载失败，请稍后重试')
  } finally {
    accountLoading.value = false
  }
}

async function loadWeComMapping() {
  try {
    weComMapping.value = await api.getEmailWeComMapping()
    weComForm.userId = weComMapping.value.weComUserId || ''
    weComForm.enabled = weComMapping.value.enabled
  } catch {
    weComMapping.value = undefined
  }
}

async function saveWeComMapping() {
  const userId = weComForm.userId.trim()
  if (!userId) {
    ElMessage.warning('请填写企业微信 UserId')
    return
  }
  savingWeCom.value = true
  try {
    weComMapping.value = await api.saveEmailWeComMapping(userId, weComForm.enabled)
    ElMessage.success('企业微信推送身份已保存')
  } catch (error: unknown) {
    ElMessage.error(errorText(error, '企业微信推送身份保存失败'))
  } finally {
    savingWeCom.value = false
  }
}

async function saveAccount() {
  const emailAddress = bindForm.emailAddress.trim()
  const appPassword = bindForm.appPassword
  if (!emailAddress || (!appPassword && !account.value?.credentialConfigured)) {
    ElMessage.warning('请填写企业邮箱地址和第三方客户端安全密码')
    return
  }
  savingAccount.value = true
  try {
    account.value = await api.saveEmailAccount({ emailAddress, appPassword })
    bindForm.appPassword = ''
    settingsVisible.value = false
    ElMessage.success(`${emailAddress} 已绑定`)
    await Promise.all([loadDigest(), loadMessages(), loadSyncStatus(false)])
  } catch (error: unknown) {
    bindForm.appPassword = ''
    ElMessage.error(errorText(error, '邮箱绑定失败，请检查填写内容'))
  } finally {
    savingAccount.value = false
  }
}

function openSettings() {
  bindForm.emailAddress = account.value?.emailAddress || ''
  bindForm.appPassword = ''
  settingsVisible.value = true
}

async function testConnection() {
  testingConnection.value = true
  try {
    const result = await api.testEmailAccount()
    bindForm.appPassword = ''
    result.success ? ElMessage.success(result.message || '连接测试成功') : ElMessage.error(result.message || '连接测试失败')
  } catch {
    bindForm.appPassword = ''
    ElMessage.error('连接测试失败，请检查邮箱配置')
  } finally {
    testingConnection.value = false
  }
}

async function removeAccount() {
  try {
    await ElMessageBox.confirm('解绑后将停止后续同步，已收取邮件不会在此操作中展示。', '解绑邮箱', {
      type: 'warning',
      confirmButtonText: '确认解绑',
      cancelButtonText: '取消'
    })
    await api.removeEmailAccount()
    stopSyncPolling()
    account.value = {
      configured: false,
      enabled: false,
      provider: 'ALIBABA_CLOUD_ENTERPRISE_MAIL',
      credentialConfigured: false
    }
    bindForm.emailAddress = ''
    bindForm.appPassword = ''
    settingsVisible.value = false
    digest.value = undefined
    messages.value = emptyMessagePage()
    ElMessage.success('邮箱已解绑')
  } catch (error: unknown) {
    if (error instanceof Error) ElMessage.error(errorText(error, '邮箱解绑失败'))
  }
}

async function loadDigest() {
  digestLoading.value = true
  digestError.value = ''
  try {
    digest.value = await api.getEmailDigest(selectedDate.value)
  } catch (error: unknown) {
    digestError.value = errorText(error, '邮件摘要加载失败，收件箱仍可正常使用')
  } finally {
    digestLoading.value = false
  }
}

async function changeDigestDate(days: number) {
  selectedDate.value = shiftDate(selectedDate.value, days)
  await loadDigest()
}

async function regenerateDigest() {
  regeneratingDigest.value = true
  try {
    const result = await api.regenerateEmailDigest(selectedDate.value)
    if ('businessDate' in result) digest.value = result
    ElMessage.success('摘要已进入生成队列，可继续阅读邮件')
    window.setTimeout(() => void loadDigest(), 800)
  } catch (error: unknown) {
    ElMessage.error(errorText(error, '摘要重新生成失败'))
  } finally {
    regeneratingDigest.value = false
  }
}

async function loadMessages() {
  messagesLoading.value = true
  messagesError.value = ''
  try {
    messages.value = await api.getEmailMessages({
      page: inboxFilters.page,
      size: pageSize,
      date: inboxFilters.date || undefined,
      keyword: inboxFilters.keyword.trim() || undefined
    })
  } catch (error: unknown) {
    messagesError.value = errorText(error, '收件箱加载失败，请重试')
  } finally {
    messagesLoading.value = false
  }
}

function searchMessages() {
  inboxFilters.page = 1
  void loadMessages()
}

function resetMessageFilters() {
  inboxFilters.date = ''
  inboxFilters.keyword = ''
  inboxFilters.page = 1
  void loadMessages()
}

function changeMessagePage(page: number) {
  inboxFilters.page = page
  void loadMessages()
}

async function openMessage(messageOrId: EmailMessageSummary | number) {
  const id = typeof messageOrId === 'number' ? messageOrId : messageOrId.id
  detailMessageId.value = id
  detailActiveTab.value = 'original'
  detailVisible.value = true
  detailLoading.value = true
  detailError.value = ''
  selectedMessage.value = undefined
  try {
    const detail = await api.getEmailMessage(id)
    detail.interpretation ||= {
      status: 'NOT_GENERATED', keyPoints: [], actionItems: [], risks: []
    }
    selectedMessage.value = detail
  } catch (error: unknown) {
    detailError.value = errorText(error, '邮件详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

async function navigateMessage(direction: -1 | 1) {
  const target = direction < 0 ? previousMessage.value : nextMessage.value
  if (target) await openMessage(target)
}

async function copyMessageInfo() {
  if (!selectedMessage.value) return
  const message = selectedMessage.value
  const text = [
    message.subject || '（无主题）',
    `发件人：${message.fromName || message.fromAddress} <${message.fromAddress}>`,
    `时间：${formatFullDateTime(message.receivedAt)}`,
    message.messageId ? `Message-ID：${message.messageId}` : ''
  ].filter(Boolean).join('\n')
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('邮件信息已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择邮件信息')
  }
}

function retryMessageDetail() {
  if (detailMessageId.value) void openMessage(detailMessageId.value)
}

async function handleDetailTabChange(name: string | number) {
  detailActiveTab.value = String(name)
  if (name === 'ai' && selectedMessage.value?.interpretation.status === 'NOT_GENERATED') {
    await generateInterpretation(false)
  }
}

async function generateInterpretation(force: boolean) {
  if (!selectedMessage.value || interpreting.value) return
  if (!force && selectedMessage.value.interpretation.status === 'SUCCESS') return
  interpreting.value = true
  selectedMessage.value.interpretation.status = 'GENERATING'
  try {
    selectedMessage.value.interpretation = await api.generateEmailInterpretation(selectedMessage.value.id)
    if (selectedMessage.value.interpretation.status === 'SUCCESS') {
      ElMessage.success('AI 解读已生成并保存')
    } else {
      ElMessage.error(selectedMessage.value.interpretation.errorMessage || 'AI 解读失败')
    }
  } catch (error: unknown) {
    selectedMessage.value.interpretation.status = 'FAILED'
    selectedMessage.value.interpretation.errorMessage = errorText(error, 'AI 解读失败')
    ElMessage.error(selectedMessage.value.interpretation.errorMessage)
  } finally {
    interpreting.value = false
  }
}

function interpretationPriorityType(priority?: string) {
  if (priority === '高' || priority?.toUpperCase() === 'HIGH') return 'danger'
  if (priority === '中' || priority?.toUpperCase() === 'MEDIUM') return 'warning'
  return 'info'
}

async function startSync() {
  if (syncRunning.value) return
  try {
    syncStatus.value = await api.startEmailSync()
    if (syncStatus.value.status === 'QUEUED') syncStatus.value.status = 'RUNNING'
    startSyncPolling()
  } catch (error: unknown) {
    syncStatus.value = { status: 'FAILED', message: errorText(error, '同步启动失败') }
  }
}

async function loadSyncStatus(refreshOnSuccess: boolean) {
  try {
    const previous = syncStatus.value.status
    const next = await api.getEmailSyncStatus()
    if (next.status === 'QUEUED') next.status = 'RUNNING'
    syncStatus.value = next
    if (next.status === 'RUNNING') {
      startSyncPolling()
    } else if (previous === 'RUNNING' && next.status === 'SUCCESS' && refreshOnSuccess) {
      stopSyncPolling()
      await Promise.all([loadMessages(), loadDigest()])
    } else {
      stopSyncPolling()
    }
  } catch {
    stopSyncPolling()
    syncStatus.value = { status: 'FAILED', message: '同步状态获取失败，请稍后刷新' }
  }
}

function startSyncPolling() {
  stopSyncPolling()
  syncPollTimer = window.setInterval(() => void loadSyncStatus(true), 350)
}

function stopSyncPolling() {
  if (syncPollTimer !== undefined) {
    window.clearInterval(syncPollTimer)
    syncPollTimer = undefined
  }
}

function digestItemTitle(item: EmailDigestItem) {
  return item.title || item.subject || '关联邮件'
}

function digestItemContent(item: EmailDigestItem) {
  return item.content || item.summary || item.action || item.sender || '点击查看邮件详情'
}

onMounted(loadAccount)
onBeforeUnmount(stopSyncPolling)
</script>

<template>
  <div class="email-page" v-loading="accountLoading">
    <el-alert
      v-if="accountError"
      :title="accountError"
      type="error"
      :closable="false"
      show-icon
    >
      <template #default><el-button size="small" @click="loadAccount">重试</el-button></template>
    </el-alert>

    <section v-else-if="!accountLoading && !configured" class="bind-card">
      <div class="bind-intro">
        <span class="eyebrow">PERSONAL INBOX</span>
        <h2>绑定阿里云企业邮箱</h2>
        <p>首个版本仅支持阿里云企业邮箱的个人收件箱（Inbox），不会读取已发送、草稿或其他文件夹。</p>
        <ul>
          <li>首次同步最近 7 个自然日，此后每小时增量收取</li>
          <li>系统只保存正文纯文本和附件元数据</li>
          <li>请使用第三方客户端安全密码，不是网页登录密码</li>
        </ul>
      </div>
      <el-form class="bind-form" label-position="top" @submit.prevent="saveAccount">
        <el-form-item label="企业邮箱地址">
          <el-input v-model="bindForm.emailAddress" type="email" autocomplete="email" aria-label="企业邮箱地址" placeholder="name@company.com" />
        </el-form-item>
        <el-form-item label="第三方客户端安全密码">
          <el-input v-model="bindForm.appPassword" type="password" autocomplete="new-password" aria-label="第三方客户端安全密码" show-password placeholder="在邮箱安全设置中生成" />
          <span class="field-tip">安全密码仅用于服务端连接测试与同步，保存后不会回显。</span>
        </el-form-item>
        <el-button native-type="submit" type="primary" size="large" :loading="savingAccount">保存并绑定</el-button>
      </el-form>
    </section>

    <template v-else-if="configured">
      <header class="account-header">
        <div>
          <span class="eyebrow">ALIBABA CLOUD ENTERPRISE MAIL</span>
          <div class="account-title-row">
            <h2>{{ maskedAddress }}</h2>
            <el-tag :type="syncStatus.status === 'FAILED' ? 'danger' : syncRunning ? 'warning' : 'success'">
              {{ accountStatusText }}
            </el-tag>
          </div>
          <p>上次同步：{{ formatDateTime(account?.lastSyncAt || syncStatus.finishedAt || syncStatus.completedAt) }}</p>
          <p v-if="syncMessage" class="sync-message" :class="{ failed: syncStatus.status === 'FAILED' }">{{ syncMessage }}</p>
        </div>
        <div class="account-actions">
          <el-button type="primary" :icon="Refresh" :disabled="syncRunning" @click="startSync">
            {{ syncRunning ? '同步中…' : '立即同步' }}
          </el-button>
          <el-button :icon="Edit" @click="openSettings">账户设置</el-button>
        </div>
      </header>

      <section class="digest-panel" aria-label="每日邮件摘要">
        <div class="section-heading">
          <div>
            <span class="eyebrow">DAILY DIGEST · ASIA/SHANGHAI</span>
            <h2>每日邮件摘要</h2>
          </div>
          <div class="digest-controls">
            <el-button circle :icon="ArrowLeft" aria-label="前一天" @click="changeDigestDate(-1)" />
            <el-date-picker v-model="selectedDate" type="date" value-format="YYYY-MM-DD" :clearable="false" aria-label="摘要日期" @change="loadDigest" />
            <el-button circle :icon="ArrowRight" aria-label="后一天" @click="changeDigestDate(1)" />
            <el-button :loading="regeneratingDigest" @click="regenerateDigest">重新生成</el-button>
          </div>
        </div>

        <el-alert v-if="digestError" :title="digestError" type="error" :closable="false" show-icon />
        <div v-else v-loading="digestLoading" class="digest-content">
          <el-tabs v-if="digest" v-model="digestActiveTab" class="digest-tabs">
            <el-tab-pane label="摘要总览" name="overview">
              <section aria-label="摘要总览" class="digest-overview">
                <div class="digest-status-row">
                  <el-tag v-if="digestModeLabel" :type="digestModeType">{{ digestModeLabel }}</el-tag>
                  <el-tag v-if="pushLabel" type="info">企业微信 · {{ pushLabel }}</el-tag>
                  <span>{{ digest.mailCount }} 封邮件</span>
                  <span v-if="digest.generatedModel">模型 {{ digest.generatedModel }}</span>
                  <span v-if="digest.generatedAt">生成于 {{ formatDateTime(digest.generatedAt) }}</span>
                </div>
                <p class="overview-copy">{{ digest.overview || (digest.status === 'EMPTY' ? '当天没有收到邮件。' : '摘要正在准备中。') }}</p>
                <p v-if="digest.pushMessage" class="push-message">{{ digest.pushMessage }}</p>
              </section>
            </el-tab-pane>
            <el-tab-pane :label="`重要邮件 ${digest.importantItems.length}`" name="important">
              <section aria-label="重要邮件" class="digest-tab-list important">
                <button v-for="item in digest.importantItems" :key="`important-${item.messageId}`" type="button" class="digest-item" @click="openMessage(item.messageId)"><strong>{{ digestItemTitle(item) }}</strong><span>{{ digestItemContent(item) }}</span></button>
                <el-empty v-if="!digest.importantItems.length" description="暂无重要邮件" :image-size="70" />
              </section>
            </el-tab-pane>
            <el-tab-pane :label="`待办事项 ${digest.todos.length}`" name="todos">
              <section aria-label="待办事项" class="digest-tab-list todo">
                <button v-for="item in digest.todos" :key="`todo-${item.messageId}`" type="button" class="digest-item" @click="openMessage(item.messageId)"><strong>{{ digestItemTitle(item) }}</strong><span>{{ digestItemContent(item) }}</span></button>
                <el-empty v-if="!digest.todos.length" description="暂无待办事项" :image-size="70" />
              </section>
            </el-tab-pane>
            <el-tab-pane :label="`风险提醒 ${digest.risks.length}`" name="risks">
              <section aria-label="风险提醒" class="digest-tab-list risk">
                <button v-for="item in digest.risks" :key="`risk-${item.messageId}`" type="button" class="digest-item" @click="openMessage(item.messageId)"><strong>{{ digestItemTitle(item) }}</strong><span>{{ digestItemContent(item) }}</span></button>
                <el-empty v-if="!digest.risks.length" description="暂无风险提醒" :image-size="70" />
              </section>
            </el-tab-pane>
            <el-tab-pane :label="`回复建议 ${digest.replySuggestions.length}`" name="replies">
              <section aria-label="回复建议" class="digest-tab-list reply">
                <button v-for="item in digest.replySuggestions" :key="`reply-${item.messageId}`" type="button" class="digest-item" @click="openMessage(item.messageId)"><strong>{{ digestItemTitle(item) }}</strong><span>{{ digestItemContent(item) }}</span></button>
                <el-empty v-if="!digest.replySuggestions.length" description="暂无回复建议" :image-size="70" />
              </section>
            </el-tab-pane>
          </el-tabs>
          <el-empty v-else description="该日期暂无摘要" />
        </div>
      </section>

      <section class="inbox-panel">
        <div class="section-heading inbox-heading">
          <div><span class="eyebrow">INBOX</span><h2>收件箱</h2></div>
          <div class="inbox-filters">
            <el-date-picker v-model="inboxFilters.date" type="date" value-format="YYYY-MM-DD" clearable placeholder="收件日期" aria-label="收件日期" />
            <el-input v-model="inboxFilters.keyword" clearable placeholder="搜索发件人或主题" aria-label="搜索邮件" :prefix-icon="Search" @keyup.enter="searchMessages" />
            <el-button type="primary" @click="searchMessages">筛选</el-button>
            <el-button @click="resetMessageFilters">重置</el-button>
          </div>
        </div>
        <el-alert v-if="messagesError" :title="messagesError" type="error" :closable="false" show-icon />
        <div v-else aria-label="收件箱列表" class="message-list" v-loading="messagesLoading">
          <button v-for="message in messages.records" :key="message.id" type="button" class="message-row" @click="openMessage(message)">
            <div class="sender-cell"><strong>{{ message.fromName || message.fromAddress }}</strong><span>{{ message.fromAddress }}</span></div>
            <div class="message-copy"><strong>{{ message.subject || '（无主题）' }}</strong><span>{{ message.preview || '暂无正文预览' }}</span></div>
            <div class="message-meta"><span>{{ formatDateTime(message.receivedAt) }}</span><span v-if="message.hasAttachments">📎 {{ message.attachmentCount }} 个附件</span></div>
          </button>
          <el-empty v-if="!messagesLoading && !messages.records.length" description="当前筛选条件下暂无邮件" />
        </div>
        <el-pagination v-if="messages.total > pageSize" class="pagination" background layout="prev, pager, next" :page-size="pageSize" :total="messages.total" :current-page="inboxFilters.page" @current-change="changeMessagePage" />
      </section>
    </template>

    <el-dialog v-model="settingsVisible" title="账户设置" width="min(520px, calc(100vw - 24px))" @closed="bindForm.appPassword = ''">
      <el-form label-position="top" @submit.prevent="saveAccount">
        <el-form-item label="企业邮箱地址"><el-input v-model="bindForm.emailAddress" type="email" aria-label="企业邮箱地址" /></el-form-item>
        <el-form-item label="第三方客户端安全密码">
          <el-input v-model="bindForm.appPassword" type="password" aria-label="第三方客户端安全密码" autocomplete="new-password" placeholder="留空保持现有安全密码" />
          <span class="field-tip">安全密码不会回显；需要更新时输入新密码。</span>
        </el-form-item>
        <el-divider content-position="left">企业微信摘要推送</el-divider>
        <el-form-item label="企业微信 UserId">
          <el-input v-model="weComForm.userId" aria-label="企业微信 UserId" placeholder="例如：zhangsan" />
          <span class="field-tip">用于内部应用点对点推送，只能填写当前员工自己的企业微信 UserId。</span>
        </el-form-item>
        <el-form-item label="启用推送">
          <el-switch v-model="weComForm.enabled" />
        </el-form-item>
        <el-button :loading="savingWeCom" :disabled="!weComForm.userId.trim()" @click="saveWeComMapping">保存推送身份</el-button>
      </el-form>
      <template #footer>
        <div class="settings-footer">
          <el-button type="danger" plain :icon="Delete" @click="removeAccount">解绑邮箱</el-button>
          <span class="footer-spacer"></span>
          <el-button :loading="testingConnection" @click="testConnection">测试连接</el-button>
          <el-button type="primary" :loading="savingAccount" @click="saveAccount">保存设置</el-button>
        </div>
      </template>
    </el-dialog>

    <el-drawer
      v-model="detailVisible"
      :with-header="false"
      size="min(860px, 92vw)"
      class="email-detail-drawer"
      append-to-body
      destroy-on-close
      aria-label="邮件详情"
    >
      <article class="mail-reader" v-loading="detailLoading">
        <header class="reader-toolbar">
          <div class="reader-location">
            <span class="reader-kicker">INBOX · 只读邮件</span>
            <span v-if="selectedMessagePosition" class="reader-position">{{ selectedMessagePosition }}</span>
          </div>
          <div class="reader-toolbar-actions">
            <el-tooltip content="上一封" placement="bottom">
              <el-button circle :icon="ArrowLeft" aria-label="上一封邮件" :disabled="!previousMessage || detailLoading" @click="navigateMessage(-1)" />
            </el-tooltip>
            <el-tooltip content="下一封" placement="bottom">
              <el-button circle :icon="ArrowRight" aria-label="下一封邮件" :disabled="!nextMessage || detailLoading" @click="navigateMessage(1)" />
            </el-tooltip>
            <span class="toolbar-divider"></span>
            <el-tooltip content="复制邮件信息" placement="bottom">
              <el-button circle :icon="CopyDocument" aria-label="复制邮件信息" :disabled="!selectedMessage" @click="copyMessageInfo" />
            </el-tooltip>
            <el-tooltip content="关闭" placement="bottom">
              <el-button circle :icon="Close" aria-label="关闭邮件详情" @click="detailVisible = false" />
            </el-tooltip>
          </div>
        </header>

        <div v-if="detailError" class="reader-error">
          <el-alert :title="detailError" type="error" :closable="false" show-icon />
          <el-button type="primary" plain @click="retryMessageDetail">重新加载</el-button>
        </div>

        <template v-else-if="selectedMessage">
          <section class="reader-hero">
            <div class="reader-subject-row">
              <div>
                <div class="reader-badges">
                  <el-tag type="info" effect="plain">收件箱</el-tag>
                  <el-tag v-if="selectedMessage.attachments.length" type="warning" effect="plain">
                    <el-icon><Paperclip /></el-icon>{{ selectedMessage.attachments.length }} 个附件
                  </el-tag>
                </div>
                <h1>{{ selectedMessage.subject || '（无主题）' }}</h1>
              </div>
              <div class="received-time">
                <el-icon><Calendar /></el-icon>
                <span>{{ formatFullDateTime(selectedMessage.receivedAt) }}</span>
              </div>
            </div>

            <div class="sender-profile">
              <div class="sender-avatar" aria-hidden="true">{{ senderInitial }}</div>
              <div class="sender-identity">
                <strong>{{ selectedMessage.fromName || selectedMessage.fromAddress }}</strong>
                <span>{{ selectedMessage.fromAddress }}</span>
              </div>
              <el-tag type="success" effect="light">发件人</el-tag>
            </div>

            <details class="recipient-details">
              <summary>
                <span>发送给 {{ selectedMessage.toAddresses.length ? selectedMessage.toAddresses.join('、') : '未知收件人' }}</span>
                <small>查看完整信头</small>
              </summary>
              <dl>
                <div><dt>发件人</dt><dd>{{ selectedMessage.fromName || selectedMessage.fromAddress }} &lt;{{ selectedMessage.fromAddress }}&gt;</dd></div>
                <div><dt>收件人</dt><dd>{{ selectedMessage.toAddresses.join('、') || '—' }}</dd></div>
                <div v-if="selectedMessage.ccAddresses.length"><dt>抄送</dt><dd>{{ selectedMessage.ccAddresses.join('、') }}</dd></div>
                <div><dt>接收时间</dt><dd>{{ formatFullDateTime(selectedMessage.receivedAt) }}</dd></div>
                <div v-if="selectedMessage.messageId"><dt>Message-ID</dt><dd class="message-id">{{ selectedMessage.messageId }}</dd></div>
              </dl>
            </details>
          </section>

          <el-tabs v-model="detailActiveTab" class="reader-tabs" @tab-change="handleDetailTabChange">
            <el-tab-pane label="邮件原文" name="original">
              <section class="reader-security-note">
                <el-icon><Lock /></el-icon>
                <div><strong>安全阅读模式</strong><span>仅展示已提取的纯文本；脚本、远程图片和邮件 HTML 均不会执行。</span></div>
              </section>

              <section class="reader-body-section">
                <div class="reader-section-title"><el-icon><Document /></el-icon><span>邮件正文</span></div>
                <div class="message-paper">
                  <pre class="message-body">{{ selectedMessage.textBody || '（邮件正文为空）' }}</pre>
                </div>
              </section>

              <section v-if="selectedMessage.attachments.length" class="attachments" aria-label="附件元数据">
                <div class="reader-section-title"><el-icon><Paperclip /></el-icon><span>附件</span><small>仅展示元数据，不下载文件内容</small></div>
                <div class="attachment-grid">
                  <article v-for="attachment in selectedMessage.attachments" :key="`${attachment.fileName}-${attachment.size}`" class="attachment-card">
                    <div class="attachment-type">{{ attachmentIcon(attachment.contentType) }}</div>
                    <div class="attachment-info"><strong>{{ attachment.fileName }}</strong><span>{{ attachment.contentType || '未知类型' }}</span></div>
                    <span class="attachment-size">{{ formatBytes(attachment.size) }}</span>
                  </article>
                </div>
              </section>
            </el-tab-pane>

            <el-tab-pane name="ai">
              <template #label><span class="ai-tab-label">✦ AI 解读<el-tag v-if="selectedMessage.interpretation.status === 'SUCCESS'" type="success" size="small">已生成</el-tag></span></template>
              <section class="ai-interpretation" aria-label="AI 解读">
                <div v-if="interpreting || selectedMessage.interpretation.status === 'GENERATING'" class="ai-generating">
                  <div class="ai-orb">✦</div><h3>正在深度解读这封邮件</h3><p>AI 正在提取核心结论、待办、风险与回复建议，完成后会自动保存。</p><el-progress :percentage="70" :indeterminate="true" :duration="2" />
                </div>
                <div v-else-if="selectedMessage.interpretation.status === 'FAILED'" class="ai-empty-state failed">
                  <div class="ai-orb">!</div><h3>AI 解读失败</h3><p>{{ selectedMessage.interpretation.errorMessage || '请稍后重试。' }}</p><el-button type="primary" @click="generateInterpretation(true)">重新解读</el-button>
                </div>
                <div v-else-if="selectedMessage.interpretation.status === 'SUCCESS'" class="ai-result">
                  <header class="ai-result-head"><div><span class="reader-kicker">AI INTERPRETATION</span><h2>邮件智能解读</h2></div><div class="ai-result-actions"><el-tag type="success">{{ selectedMessage.interpretation.model || 'AI' }}</el-tag><el-button plain :loading="interpreting" @click="generateInterpretation(true)">重新解读</el-button></div></header>
                  <section class="ai-summary-card"><span>核心结论</span><p>{{ selectedMessage.interpretation.summary || '暂无核心结论' }}</p></section>
                  <section class="ai-intent-card"><strong>发件人意图</strong><p>{{ selectedMessage.interpretation.senderIntent || '暂无意图判断' }}</p></section>
                  <div class="ai-analysis-grid">
                    <section class="ai-analysis-card points"><h3>关键要点</h3><ul><li v-for="(point, index) in selectedMessage.interpretation.keyPoints" :key="`point-${index}`">{{ point }}</li></ul><p v-if="!selectedMessage.interpretation.keyPoints.length">暂无关键要点</p></section>
                    <section class="ai-analysis-card actions"><h3>待办事项</h3><article v-for="(action, index) in selectedMessage.interpretation.actionItems" :key="`action-${index}`" class="ai-action-item"><div><strong>{{ action.content || '待办事项' }}</strong><span v-if="action.deadline">截止：{{ action.deadline }}</span></div><el-tag :type="interpretationPriorityType(action.priority)" size="small">{{ action.priority || '普通' }}</el-tag></article><p v-if="!selectedMessage.interpretation.actionItems.length">暂无明确待办</p></section>
                    <section class="ai-analysis-card risks"><h3>风险提醒</h3><ul><li v-for="(risk, index) in selectedMessage.interpretation.risks" :key="`risk-${index}`">{{ risk }}</li></ul><p v-if="!selectedMessage.interpretation.risks.length">未识别到明显风险</p></section>
                    <section class="ai-analysis-card reply"><h3>建议回复</h3><pre>{{ selectedMessage.interpretation.replySuggestion || '暂无回复建议' }}</pre></section>
                  </div>
                  <footer class="ai-result-foot">由 {{ selectedMessage.interpretation.model || 'AI' }} 生成 · {{ formatDateTime(selectedMessage.interpretation.generatedAt) }} · 请结合邮件原文核验</footer>
                </div>
                <div v-else class="ai-empty-state">
                  <div class="ai-orb">✦</div><h3>让 AI 帮你快速读懂邮件</h3><p>点击后将生成核心结论、关键要点、待办、风险与回复建议，并保存到邮件记录。</p><el-button type="primary" size="large" @click="generateInterpretation(false)">开始 AI 解读</el-button>
                </div>
              </section>
            </el-tab-pane>
          </el-tabs>

        </template>

        <el-empty v-else-if="!detailLoading" description="请选择一封邮件查看详情" />
      </article>
    </el-drawer>
  </div>
</template>

<style scoped>
.email-page { display: flex; flex-direction: column; gap: 20px; min-width: 0; text-align: left; color: var(--gray-700); }
.email-page *, .email-page *::before, .email-page *::after { box-sizing: border-box; }
.bind-card, .account-header, .digest-panel, .inbox-panel { background: #fff; border: 1px solid var(--gray-200); border-radius: 16px; box-shadow: 0 8px 24px rgba(15, 23, 42, .04); }
.bind-card { max-width: 900px; width: 100%; margin: 32px auto; padding: 36px; display: grid; grid-template-columns: 1.1fr .9fr; gap: 48px; }
.bind-intro h2, .account-header h2, .section-heading h2 { margin: 4px 0 8px; font-size: 22px; color: var(--gray-900); }
.bind-intro p { line-height: 1.7; color: var(--gray-600); }
.bind-intro ul { margin: 24px 0 0; padding-left: 20px; color: var(--gray-600); line-height: 2; }
.bind-form { padding: 24px; border-radius: 12px; background: var(--gray-50); }
.bind-form .el-button { width: 100%; }
.eyebrow { color: var(--primary); font-size: 11px; font-weight: 700; letter-spacing: .1em; }
.field-tip { display: block; margin-top: 6px; color: var(--gray-500); font-size: 12px; line-height: 1.5; }
.account-header { padding: 22px 24px; display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.account-title-row { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.account-header p { color: var(--gray-500); font-size: 13px; }
.account-header .sync-message { margin-top: 5px; color: var(--success); }
.account-header .sync-message.failed { color: var(--danger); }
.account-actions { display: flex; flex-shrink: 0; }
.digest-panel, .inbox-panel { padding: 24px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.digest-controls, .inbox-filters { display: flex; align-items: center; gap: 8px; }
.digest-controls :deep(.el-date-editor) { width: 145px; }
.digest-content { min-height: 120px; }
.digest-overview { border-radius: 12px; padding: 18px; background: linear-gradient(135deg, #eef2ff, #f8fafc); border: 1px solid #e0e7ff; }
.digest-status-row { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; color: var(--gray-500); font-size: 12px; }
.overview-copy { margin-top: 12px; color: var(--gray-800); font-size: 16px; line-height: 1.7; }
.push-message { margin-top: 8px; color: var(--gray-500); font-size: 13px; }
.digest-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 14px; }
.digest-card { min-width: 0; padding: 16px; border: 1px solid var(--gray-200); border-radius: 12px; border-top-width: 3px; }
.digest-card.important { border-top-color: #6366f1; }.digest-card.todo { border-top-color: #0ea5e9; }.digest-card.risk { border-top-color: #f97316; }.digest-card.reply { border-top-color: #10b981; }
.digest-card h3 { margin: 0 0 10px; font-size: 14px; color: var(--gray-800); }
.digest-item { display: flex; flex-direction: column; width: 100%; min-width: 0; padding: 10px 0; border: 0; border-top: 1px solid var(--gray-100); background: transparent; text-align: left; cursor: pointer; }
.digest-item:hover strong { color: var(--primary); }.digest-item strong, .digest-item span { overflow-wrap: anywhere; }.digest-item strong { color: var(--gray-800); font-size: 13px; }.digest-item span, .empty-copy { margin-top: 4px; color: var(--gray-500); font-size: 12px; line-height: 1.5; }
.inbox-heading { align-items: flex-end; }.inbox-filters :deep(.el-date-editor) { width: 145px; }.inbox-filters :deep(.el-input) { width: 230px; }
.message-list { min-height: 120px; border-top: 1px solid var(--gray-200); }
.message-row { width: 100%; display: grid; grid-template-columns: minmax(140px, .8fr) minmax(240px, 2fr) minmax(150px, .7fr); gap: 18px; align-items: center; padding: 16px 8px; border: 0; border-bottom: 1px solid var(--gray-100); background: #fff; text-align: left; cursor: pointer; }
.message-row:hover { background: var(--gray-50); }.sender-cell, .message-copy, .message-meta { display: flex; flex-direction: column; gap: 4px; min-width: 0; }.sender-cell strong, .message-copy strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--gray-800); font-size: 13px; }.sender-cell span, .message-copy span, .message-meta span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--gray-500); font-size: 12px; }.message-meta { align-items: flex-end; }
.pagination { justify-content: flex-end; margin-top: 18px; }.settings-footer { display: flex; width: 100%; }.footer-spacer { flex: 1; }
.mail-reader { min-height: 100%; background: #f7f8fb; color: var(--gray-700); }
.reader-toolbar { position: sticky; top: 0; z-index: 4; display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 64px; padding: 11px 22px; border-bottom: 1px solid #e5e7eb; background: rgba(255, 255, 255, .94); backdrop-filter: blur(14px); }
.reader-location, .reader-toolbar-actions { display: flex; align-items: center; gap: 9px; }.reader-kicker { color: #596273; font-size: 11px; font-weight: 700; letter-spacing: .08em; }.reader-position { padding: 3px 8px; border-radius: 999px; background: #eef2f7; color: #727b8b; font-size: 11px; }.toolbar-divider { width: 1px; height: 24px; background: #e5e7eb; }
.reader-error { display: flex; flex-direction: column; gap: 16px; padding: 30px; }
.reader-hero { padding: 34px 40px 22px; background: #fff; border-bottom: 1px solid #e8ebf0; }.reader-subject-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 28px; }.reader-badges { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }.reader-badges .el-tag { gap: 4px; }.reader-hero h1 { max-width: 650px; margin: 13px 0 0; color: #202938; font-size: clamp(24px, 3vw, 34px); font-weight: 700; line-height: 1.32; letter-spacing: -.025em; overflow-wrap: anywhere; }.received-time { display: flex; align-items: center; gap: 7px; padding-top: 5px; color: #778193; font-size: 12px; white-space: nowrap; }
.sender-profile { display: flex; align-items: center; gap: 12px; margin-top: 28px; }.sender-avatar { display: flex; align-items: center; justify-content: center; width: 46px; height: 46px; flex-shrink: 0; border-radius: 14px; background: linear-gradient(135deg, #5368d8, #7c5bd8); box-shadow: 0 8px 20px rgba(83,104,216,.22); color: #fff; font-size: 18px; font-weight: 700; }.sender-identity { display: flex; flex-direction: column; gap: 3px; min-width: 0; }.sender-identity strong { color: #2b3443; font-size: 15px; }.sender-identity span { color: #7a8494; font-size: 12px; overflow-wrap: anywhere; }.sender-profile .el-tag { margin-left: auto; }
.recipient-details { margin: 16px 0 0 58px; border-top: 1px solid #f0f1f4; }.recipient-details summary { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 11px 0 2px; color: #707b8c; font-size: 12px; cursor: pointer; list-style: none; }.recipient-details summary::-webkit-details-marker { display: none; }.recipient-details summary small { color: var(--primary); }.recipient-details dl { margin: 10px 0 0; padding: 14px 16px; border-radius: 10px; background: #f8f9fb; }.recipient-details dl div { display: grid; grid-template-columns: 78px minmax(0, 1fr); gap: 10px; padding: 5px 0; }.recipient-details dt { color: #8a93a2; }.recipient-details dd { margin: 0; color: #566173; overflow-wrap: anywhere; }.message-id { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; }
.reader-security-note { display: flex; align-items: center; gap: 12px; margin: 20px 40px 0; padding: 13px 16px; border: 1px solid #dce9df; border-radius: 12px; background: #f3faf5; color: #427053; }.reader-security-note > .el-icon { flex-shrink: 0; font-size: 20px; }.reader-security-note div { display: flex; flex-direction: column; gap: 2px; }.reader-security-note strong { font-size: 13px; }.reader-security-note span { color: #648170; font-size: 12px; line-height: 1.5; }
.reader-body-section, .attachments { padding: 24px 40px 0; }.reader-section-title { display: flex; align-items: center; gap: 8px; margin-bottom: 11px; color: #4d5869; font-size: 13px; font-weight: 700; }.reader-section-title .el-icon { color: #7785a6; font-size: 16px; }.reader-section-title small { margin-left: auto; color: #8a93a2; font-size: 11px; font-weight: 400; }.message-paper { min-height: 300px; padding: clamp(24px, 4vw, 42px); border: 1px solid #e1e4ea; border-radius: 14px; background: #fff; box-shadow: 0 12px 35px rgba(30, 41, 59, .06); }.message-body { margin: 0; border: 0; background: transparent; color: #303947; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", sans-serif; font-size: 15px; line-height: 1.95; white-space: pre-wrap; overflow-wrap: anywhere; tab-size: 4; }
.attachments { padding-bottom: 38px; }.attachment-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }.attachment-card { display: flex; align-items: center; gap: 12px; min-width: 0; padding: 14px; border: 1px solid #e2e5eb; border-radius: 12px; background: #fff; transition: border-color .18s, box-shadow .18s, transform .18s; }.attachment-card:hover { border-color: #c6d0ea; box-shadow: 0 8px 22px rgba(30,41,59,.07); transform: translateY(-1px); }.attachment-type { display: flex; align-items: center; justify-content: center; width: 44px; height: 44px; flex-shrink: 0; border-radius: 10px; background: #eef2ff; color: #5368d8; font-size: 10px; font-weight: 800; letter-spacing: .03em; }.attachment-info { display: flex; flex-direction: column; gap: 4px; min-width: 0; }.attachment-info strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #394353; font-size: 13px; }.attachment-info span, .attachment-size { color: #8a93a2; font-size: 11px; }.attachment-size { margin-left: auto; white-space: nowrap; }
:global(.email-detail-drawer) { width: min(860px, 92vw) !important; }.email-detail-drawer :deep(.el-drawer__body) { padding: 0; overflow: auto; }.email-detail-drawer :deep(.el-loading-mask) { z-index: 5; }
@media (max-width: 820px) { .bind-card { grid-template-columns: 1fr; gap: 24px; padding: 24px; }.account-header, .section-heading { align-items: flex-start; flex-direction: column; }.account-actions, .account-actions .el-button { width: 100%; }.digest-controls, .inbox-filters { width: 100%; flex-wrap: wrap; }.inbox-filters :deep(.el-date-editor), .inbox-filters :deep(.el-input) { flex: 1 1 180px; width: auto; }.message-row { grid-template-columns: 1fr; gap: 8px; }.message-meta { align-items: flex-start; flex-direction: row; flex-wrap: wrap; }.message-meta span { white-space: normal; }.settings-footer { flex-wrap: wrap; gap: 8px; }.settings-footer .el-button { margin-left: 0; }.footer-spacer { display: none; flex-basis: 100%; }.digest-grid { grid-template-columns: 1fr; } }
@media (max-width: 700px) { :global(.email-detail-drawer) { width: 100vw !important; }.reader-toolbar { padding: 9px 13px; }.reader-kicker { display: none; }.reader-hero { padding: 24px 18px 18px; }.reader-subject-row { flex-direction: column-reverse; gap: 12px; }.reader-hero h1 { font-size: 25px; }.received-time { padding: 0; white-space: normal; }.recipient-details { margin-left: 0; }.reader-security-note { margin: 14px 18px 0; }.reader-body-section, .attachments { padding-right: 18px; padding-left: 18px; }.message-paper { padding: 22px 18px; border-radius: 11px; }.attachment-grid { grid-template-columns: 1fr; }.reader-section-title small { display: none; } }
@media (max-width: 480px) { .digest-panel, .inbox-panel { padding: 16px; }.bind-card { margin: 0; padding: 18px; }.digest-controls .el-button:last-child { flex: 1; }.digest-controls :deep(.el-date-editor) { flex: 1; width: 120px; }.account-actions { flex-direction: column; gap: 8px; }.account-actions .el-button { margin-left: 0; }.reader-toolbar-actions .el-button:nth-child(3), .toolbar-divider { display: none; }.sender-profile .el-tag { display: none; }.message-body { font-size: 14px; line-height: 1.85; } }


.digest-tabs :deep(.el-tabs__header) { margin: 0 0 16px; }.digest-tabs :deep(.el-tabs__nav-wrap::after), .reader-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background: #e7eaf0; }.digest-tabs :deep(.el-tabs__item) { height: 46px; color: #687386; font-weight: 600; }.digest-tabs :deep(.el-tabs__item.is-active) { color: var(--primary); }.digest-tab-list { min-height: 150px; padding: 4px 18px 14px; border: 1px solid #e4e7ed; border-radius: 12px; background: #fff; }.digest-tab-list.important { border-top: 3px solid #6366f1; }.digest-tab-list.todo { border-top: 3px solid #0ea5e9; }.digest-tab-list.risk { border-top: 3px solid #f97316; }.digest-tab-list.reply { border-top: 3px solid #10b981; }.digest-tab-list .digest-item:first-child { border-top: 0; }
.reader-tabs { padding-top: 8px; }.reader-tabs :deep(.el-tabs__header) { position: sticky; top: 64px; z-index: 3; margin: 0; padding: 0 40px; background: rgba(247,248,251,.96); backdrop-filter: blur(12px); }.reader-tabs :deep(.el-tabs__item) { height: 52px; padding: 0 22px; color: #687386; font-weight: 700; }.reader-tabs :deep(.el-tabs__content) { overflow: visible; }.ai-tab-label { display: inline-flex; align-items: center; gap: 7px; }.ai-tab-label .el-tag { height: 20px; padding: 0 6px; font-size: 10px; }
.ai-interpretation { min-height: 470px; padding: 26px 40px 40px; }.ai-empty-state, .ai-generating { display: flex; align-items: center; flex-direction: column; justify-content: center; min-height: 400px; padding: 40px; border: 1px dashed #cfd7ed; border-radius: 16px; background: radial-gradient(circle at 50% 0, #f1f3ff, #fff 62%); text-align: center; }.ai-empty-state.failed { border-color: #f3c7c7; background: #fff8f8; }.ai-orb { display: flex; align-items: center; justify-content: center; width: 68px; height: 68px; margin-bottom: 18px; border-radius: 22px; background: linear-gradient(135deg, #5368d8, #9b5de5); box-shadow: 0 14px 34px rgba(83,104,216,.25); color: #fff; font-size: 30px; }.ai-empty-state.failed .ai-orb { background: linear-gradient(135deg, #d95c5c, #e77e64); }.ai-empty-state h3, .ai-generating h3 { margin: 0; color: #2c3545; font-size: 20px; }.ai-empty-state p, .ai-generating p { max-width: 480px; margin: 10px 0 22px; color: #778193; line-height: 1.7; }.ai-generating .el-progress { width: min(360px, 90%); }
.ai-result { display: flex; flex-direction: column; gap: 14px; }.ai-result-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }.ai-result-head h2 { margin: 5px 0 0; color: #293344; font-size: 22px; }.ai-result-actions { display: flex; align-items: center; gap: 8px; }.ai-summary-card { padding: 20px 22px; border: 1px solid #d9ddf5; border-radius: 14px; background: linear-gradient(135deg, #f0f2ff, #fbfbff); }.ai-summary-card span { color: #6170b5; font-size: 11px; font-weight: 800; letter-spacing: .08em; }.ai-summary-card p { margin: 8px 0 0; color: #303b50; font-size: 17px; font-weight: 600; line-height: 1.65; }.ai-intent-card { display: grid; grid-template-columns: 110px 1fr; gap: 14px; padding: 16px 20px; border-left: 4px solid #7081cf; border-radius: 10px; background: #fff; box-shadow: 0 5px 18px rgba(30,41,59,.04); }.ai-intent-card strong { color: #596579; }.ai-intent-card p { margin: 0; color: #3e4858; line-height: 1.65; }
.ai-analysis-grid { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 12px; }.ai-analysis-card { min-height: 150px; padding: 18px; border: 1px solid #e1e5ec; border-radius: 13px; background: #fff; }.ai-analysis-card h3 { margin: 0 0 12px; color: #3d4859; font-size: 14px; }.ai-analysis-card ul { margin: 0; padding-left: 19px; }.ai-analysis-card li { margin: 7px 0; color: #596579; line-height: 1.55; }.ai-analysis-card > p { color: #8a93a2; font-size: 12px; }.ai-analysis-card.points { border-top: 3px solid #6366f1; }.ai-analysis-card.actions { border-top: 3px solid #0ea5e9; }.ai-analysis-card.risks { border-top: 3px solid #f97316; }.ai-analysis-card.reply { border-top: 3px solid #10b981; }.ai-action-item { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; padding: 9px 0; border-top: 1px solid #f0f1f4; }.ai-action-item div { display: flex; flex-direction: column; gap: 4px; }.ai-action-item strong { color: #4a5567; font-size: 13px; }.ai-action-item span { color: #8a93a2; font-size: 11px; }.ai-analysis-card.reply pre { margin: 0; color: #465365; font-family: inherit; line-height: 1.7; white-space: pre-wrap; }.ai-result-foot { color: #9098a6; font-size: 11px; text-align: right; }
@media (max-width: 700px) { .reader-tabs :deep(.el-tabs__header) { top: 59px; padding: 0 18px; }.ai-interpretation { padding: 20px 18px 30px; }.ai-analysis-grid { grid-template-columns: 1fr; }.ai-result-head { flex-direction: column; }.ai-result-actions { width: 100%; justify-content: space-between; }.ai-intent-card { grid-template-columns: 1fr; gap: 5px; }.ai-empty-state, .ai-generating { min-height: 350px; padding: 26px 18px; }.digest-tabs :deep(.el-tabs__nav) { white-space: nowrap; }.digest-tabs :deep(.el-tabs__nav-scroll) { overflow-x: auto; } }
</style>
