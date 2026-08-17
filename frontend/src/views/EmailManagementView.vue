<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, ArrowRight, Delete, Edit, Refresh, Search } from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import type {
  EmailAccount,
  EmailDailyDigest,
  EmailDigestItem,
  EmailMessageDetail,
  EmailMessagePage,
  EmailMessageSummary,
  EmailSyncStatus,
  EmailWeComMapping,
  EmailIntegrationConfig
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
const integrationConfig = ref<EmailIntegrationConfig>()
const integrationLoading = ref(false)
const integrationError = ref('')
const savingIntegration = ref(false)
const testingDeepSeek = ref(false)
const testingWeCom = ref(false)
const integrationForm = reactive({
  deepSeekEnabled: false,
  deepSeekBaseUrl: 'https://api.deepseek.com',
  deepSeekModel: 'deepseek-chat',
  deepSeekApiKey: '',
  weComEnabled: false,
  weComBaseUrl: 'https://qyapi.weixin.qq.com',
  weComCorpId: '',
  weComAgentId: '',
  weComSecret: '',
  publicBaseUrl: 'http://192.168.1.241:18080'
})
const managementRoles = new Set(['DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN'])
const currentRole = (() => {
  try { return JSON.parse(localStorage.getItem('user') || '{}')?.role as string | undefined }
  catch { return undefined }
})()
const canConfigureIntegrations = managementRoles.has(currentRole || '')

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
const selectedMessage = ref<EmailMessageDetail>()

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

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function errorText(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

async function loadIntegrationConfig() {
  if (!canConfigureIntegrations) return
  integrationLoading.value = true
  integrationError.value = ''
  try {
    const result = await api.getEmailIntegrationConfig()
    integrationConfig.value = result
    integrationForm.deepSeekEnabled = result.deepSeekEnabled
    integrationForm.deepSeekBaseUrl = result.deepSeekBaseUrl || 'https://api.deepseek.com'
    integrationForm.deepSeekModel = result.deepSeekModel || 'deepseek-chat'
    integrationForm.deepSeekApiKey = ''
    integrationForm.weComEnabled = result.weComEnabled
    integrationForm.weComBaseUrl = result.weComBaseUrl || 'https://qyapi.weixin.qq.com'
    integrationForm.weComCorpId = result.weComCorpId || ''
    integrationForm.weComAgentId = result.weComAgentId || ''
    integrationForm.weComSecret = ''
    integrationForm.publicBaseUrl = result.publicBaseUrl || 'http://192.168.1.241:18080'
  } catch (error: unknown) {
    integrationError.value = errorText(error, '集成配置加载失败')
  } finally {
    integrationLoading.value = false
  }
}

async function saveIntegrationConfig() {
  savingIntegration.value = true
  try {
    integrationConfig.value = await api.saveEmailIntegrationConfig({
      deepSeekEnabled: integrationForm.deepSeekEnabled,
      deepSeekBaseUrl: integrationForm.deepSeekBaseUrl.trim(),
      deepSeekModel: integrationForm.deepSeekModel.trim(),
      deepSeekApiKey: integrationForm.deepSeekApiKey || undefined,
      weComEnabled: integrationForm.weComEnabled,
      weComBaseUrl: integrationForm.weComBaseUrl.trim(),
      weComCorpId: integrationForm.weComCorpId.trim() || undefined,
      weComAgentId: integrationForm.weComAgentId.trim() || undefined,
      weComSecret: integrationForm.weComSecret || undefined,
      publicBaseUrl: integrationForm.publicBaseUrl.trim() || undefined
    })
    integrationForm.deepSeekApiKey = ''
    integrationForm.weComSecret = ''
    ElMessage.success('邮件集成配置已加密保存')
  } catch (error: unknown) {
    integrationForm.deepSeekApiKey = ''
    integrationForm.weComSecret = ''
    ElMessage.error(errorText(error, '邮件集成配置保存失败'))
  } finally {
    savingIntegration.value = false
  }
}

async function testDeepSeekIntegration() {
  testingDeepSeek.value = true
  try {
    const result = await api.testEmailDeepSeek()
    result.success ? ElMessage.success(result.message) : ElMessage.error(result.message)
    await loadIntegrationConfig()
  } catch (error: unknown) {
    ElMessage.error(errorText(error, 'DeepSeek 连接测试失败'))
  } finally {
    testingDeepSeek.value = false
  }
}

async function testWeComIntegration() {
  testingWeCom.value = true
  try {
    const result = await api.testEmailWeCom()
    result.success ? ElMessage.success(result.message) : ElMessage.error(result.message)
    await loadIntegrationConfig()
  } catch (error: unknown) {
    ElMessage.error(errorText(error, '企业微信连接测试失败'))
  } finally {
    testingWeCom.value = false
  }
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
  detailVisible.value = true
  detailLoading.value = true
  detailError.value = ''
  selectedMessage.value = undefined
  try {
    selectedMessage.value = await api.getEmailMessage(id)
  } catch (error: unknown) {
    detailError.value = errorText(error, '邮件详情加载失败')
  } finally {
    detailLoading.value = false
  }
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

onMounted(() => {
  void loadAccount()
  void loadIntegrationConfig()
})
onBeforeUnmount(stopSyncPolling)
</script>

<template>
  <div class="email-page" v-loading="accountLoading">
    <section v-if="canConfigureIntegrations" class="integration-panel" v-loading="integrationLoading" aria-label="邮件集成配置">
      <div class="section-heading">
        <div>
          <span class="eyebrow">ADMIN INTEGRATION</span>
          <h2>摘要与推送配置</h2>
          <p>DeepSeek API Key 与企业微信 Secret 加密保存到数据库，保存后不会回显。</p>
        </div>
        <el-button type="primary" :loading="savingIntegration" @click="saveIntegrationConfig">保存集成配置</el-button>
      </div>
      <el-alert v-if="integrationError" :title="integrationError" type="error" :closable="false" show-icon />
      <div class="integration-grid">
        <article class="integration-card">
          <header><div><h3>DeepSeek 邮件摘要</h3><p>用于生成每日重要邮件、待办、风险与回复建议。</p></div><el-switch v-model="integrationForm.deepSeekEnabled" /></header>
          <el-form label-position="top">
            <el-form-item label="服务地址"><el-input v-model="integrationForm.deepSeekBaseUrl" aria-label="DeepSeek 服务地址" /></el-form-item>
            <el-form-item label="模型"><el-input v-model="integrationForm.deepSeekModel" aria-label="DeepSeek 模型" /></el-form-item>
            <el-form-item label="API Key">
              <el-input v-model="integrationForm.deepSeekApiKey" type="password" show-password autocomplete="new-password" aria-label="DeepSeek API Key" :placeholder="integrationConfig?.deepSeekApiKeyConfigured ? '已配置；留空保持不变' : '请输入 API Key'" />
            </el-form-item>
            <div class="integration-actions"><el-tag :type="integrationConfig?.deepSeekApiKeyConfigured ? 'success' : 'info'">{{ integrationConfig?.deepSeekApiKeyConfigured ? '密钥已配置' : '密钥未配置' }}</el-tag><el-button :loading="testingDeepSeek" :disabled="!integrationConfig?.deepSeekApiKeyConfigured || !integrationConfig?.deepSeekEnabled" @click="testDeepSeekIntegration">测试连接</el-button></div>
            <p v-if="integrationConfig?.deepSeekTestMessage" class="test-message">最近测试：{{ integrationConfig.deepSeekTestMessage }} · {{ formatDateTime(integrationConfig.deepSeekTestedAt) }}</p>
          </el-form>
        </article>
        <article class="integration-card">
          <header><div><h3>企业微信内部应用</h3><p>将摘要概览与系统链接点对点推送给员工。</p></div><el-switch v-model="integrationForm.weComEnabled" /></header>
          <el-form label-position="top">
            <el-form-item label="服务地址"><el-input v-model="integrationForm.weComBaseUrl" aria-label="企业微信服务地址" /></el-form-item>
            <div class="two-column-fields"><el-form-item label="CorpId"><el-input v-model="integrationForm.weComCorpId" aria-label="企业微信 CorpId" /></el-form-item><el-form-item label="AgentId"><el-input v-model="integrationForm.weComAgentId" aria-label="企业微信 AgentId" /></el-form-item></div>
            <el-form-item label="Secret"><el-input v-model="integrationForm.weComSecret" type="password" show-password autocomplete="new-password" aria-label="企业微信 Secret" :placeholder="integrationConfig?.weComSecretConfigured ? '已配置；留空保持不变' : '请输入应用 Secret'" /></el-form-item>
            <el-form-item label="系统访问地址"><el-input v-model="integrationForm.publicBaseUrl" aria-label="系统访问地址" placeholder="http://192.168.1.241:18080" /></el-form-item>
            <div class="integration-actions"><el-tag :type="integrationConfig?.weComSecretConfigured ? 'success' : 'info'">{{ integrationConfig?.weComSecretConfigured ? 'Secret 已配置' : 'Secret 未配置' }}</el-tag><el-button :loading="testingWeCom" :disabled="!integrationConfig?.weComSecretConfigured || !integrationConfig?.weComEnabled" @click="testWeComIntegration">测试连接</el-button></div>
            <p v-if="integrationConfig?.weComTestMessage" class="test-message">最近测试：{{ integrationConfig.weComTestMessage }} · {{ formatDateTime(integrationConfig.weComTestedAt) }}</p>
          </el-form>
        </article>
      </div>
    </section>

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
          <div v-if="digest" class="digest-overview">
            <div class="digest-status-row">
              <el-tag v-if="digestModeLabel" :type="digestModeType">{{ digestModeLabel }}</el-tag>
              <el-tag v-if="pushLabel" type="info">企业微信 · {{ pushLabel }}</el-tag>
              <span>{{ digest.mailCount }} 封邮件</span>
              <span v-if="digest.generatedAt">生成于 {{ formatDateTime(digest.generatedAt) }}</span>
            </div>
            <p class="overview-copy">{{ digest.overview || (digest.status === 'EMPTY' ? '当天没有收到邮件。' : '摘要正在准备中。') }}</p>
            <p v-if="digest.pushMessage" class="push-message">{{ digest.pushMessage }}</p>
          </div>
          <el-empty v-else description="该日期暂无摘要" />

          <div v-if="digest" class="digest-grid">
            <section aria-label="重要邮件" class="digest-card important">
              <h3>重要邮件</h3>
              <button v-for="item in digest.importantItems" :key="`important-${item.messageId}`" type="button" class="digest-item" @click="openMessage(item.messageId)">
                <strong>{{ digestItemTitle(item) }}</strong><span>{{ digestItemContent(item) }}</span>
              </button>
              <p v-if="!digest.importantItems.length" class="empty-copy">暂无重要邮件</p>
            </section>
            <section aria-label="待办事项" class="digest-card todo">
              <h3>待办事项</h3>
              <button v-for="item in digest.todos" :key="`todo-${item.messageId}`" type="button" class="digest-item" @click="openMessage(item.messageId)">
                <strong>{{ digestItemTitle(item) }}</strong><span>{{ digestItemContent(item) }}</span>
              </button>
              <p v-if="!digest.todos.length" class="empty-copy">暂无待办事项</p>
            </section>
            <section aria-label="风险提醒" class="digest-card risk">
              <h3>风险提醒</h3>
              <button v-for="item in digest.risks" :key="`risk-${item.messageId}`" type="button" class="digest-item" @click="openMessage(item.messageId)">
                <strong>{{ digestItemTitle(item) }}</strong><span>{{ digestItemContent(item) }}</span>
              </button>
              <p v-if="!digest.risks.length" class="empty-copy">暂无风险提醒</p>
            </section>
            <section aria-label="回复建议" class="digest-card reply">
              <h3>回复建议</h3>
              <button v-for="item in digest.replySuggestions" :key="`reply-${item.messageId}`" type="button" class="digest-item" @click="openMessage(item.messageId)">
                <strong>{{ digestItemTitle(item) }}</strong><span>{{ digestItemContent(item) }}</span>
              </button>
              <p v-if="!digest.replySuggestions.length" class="empty-copy">暂无回复建议</p>
            </section>
          </div>
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

    <el-drawer v-model="detailVisible" title="邮件详情" size="520px" class="email-detail-drawer" append-to-body>
      <div v-loading="detailLoading" class="message-detail">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon />
        <template v-else-if="selectedMessage">
          <h2>{{ selectedMessage.subject || '（无主题）' }}</h2>
          <dl class="message-headers">
            <div><dt>发件人</dt><dd>{{ selectedMessage.fromName || selectedMessage.fromAddress }} &lt;{{ selectedMessage.fromAddress }}&gt;</dd></div>
            <div><dt>收件人</dt><dd>{{ selectedMessage.toAddresses.join('、') || '—' }}</dd></div>
            <div v-if="selectedMessage.ccAddresses.length"><dt>抄送</dt><dd>{{ selectedMessage.ccAddresses.join('、') }}</dd></div>
            <div><dt>时间</dt><dd>{{ formatDateTime(selectedMessage.receivedAt) }}</dd></div>
          </dl>
          <pre class="message-body">{{ selectedMessage.textBody || '（邮件正文为空）' }}</pre>
          <section v-if="selectedMessage.attachments.length" class="attachments" aria-label="附件元数据">
            <h3>附件（仅元数据）</h3>
            <div v-for="attachment in selectedMessage.attachments" :key="`${attachment.fileName}-${attachment.size}`" class="attachment-row">
              <span>📎 {{ attachment.fileName }}</span><span>{{ attachment.contentType || '未知类型' }} · {{ formatBytes(attachment.size) }}</span>
            </div>
          </section>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.email-page { display: flex; flex-direction: column; gap: 20px; min-width: 0; text-align: left; color: var(--gray-700); }
.email-page *, .email-page *::before, .email-page *::after { box-sizing: border-box; }
.bind-card, .integration-panel, .account-header, .digest-panel, .inbox-panel { background: #fff; border: 1px solid var(--gray-200); border-radius: 16px; box-shadow: 0 8px 24px rgba(15, 23, 42, .04); }
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
.message-detail h2 { margin: 0 0 18px; font-size: 20px; color: var(--gray-900); }.message-headers { margin: 0; padding: 14px; border-radius: 10px; background: var(--gray-50); }.message-headers div { display: grid; grid-template-columns: 58px 1fr; gap: 8px; padding: 4px 0; }.message-headers dt { color: var(--gray-500); }.message-headers dd { margin: 0; overflow-wrap: anywhere; color: var(--gray-700); }.message-body { margin: 18px 0; padding: 0; border: 0; background: transparent; color: var(--gray-800); font-family: inherit; font-size: 14px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.attachments { padding-top: 14px; border-top: 1px solid var(--gray-200); }.attachments h3 { font-size: 14px; }.attachment-row { display: flex; justify-content: space-between; gap: 12px; padding: 10px; border-radius: 8px; background: var(--gray-50); color: var(--gray-600); font-size: 12px; }
:global(.email-detail-drawer) { width: min(520px, 100vw) !important; }
@media (max-width: 820px) { .bind-card { grid-template-columns: 1fr; gap: 24px; padding: 24px; }.account-header, .section-heading { align-items: flex-start; flex-direction: column; }.account-actions, .account-actions .el-button { width: 100%; }.digest-controls, .inbox-filters { width: 100%; flex-wrap: wrap; }.inbox-filters :deep(.el-date-editor), .inbox-filters :deep(.el-input) { flex: 1 1 180px; width: auto; }.message-row { grid-template-columns: 1fr; gap: 8px; }.message-meta { align-items: flex-start; flex-direction: row; flex-wrap: wrap; }.message-meta span { white-space: normal; }.settings-footer { flex-wrap: wrap; gap: 8px; }.settings-footer .el-button { margin-left: 0; }.footer-spacer { display: none; flex-basis: 100%; }.digest-grid { grid-template-columns: 1fr; } }
@media (max-width: 480px) { .digest-panel, .inbox-panel { padding: 16px; }.bind-card { margin: 0; padding: 18px; }.digest-controls .el-button:last-child { flex: 1; }.digest-controls :deep(.el-date-editor) { flex: 1; width: 120px; }.account-actions { flex-direction: column; gap: 8px; }.account-actions .el-button { margin-left: 0; }.attachment-row { flex-direction: column; } }

.integration-panel { padding: 24px; }
.integration-panel .section-heading p { margin: 6px 0 0; color: var(--gray-500); font-size: 13px; }
.integration-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.integration-card { padding: 18px; border: 1px solid var(--gray-200); border-radius: 12px; background: var(--gray-50); }
.integration-card > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.integration-card h3 { margin: 0; color: var(--gray-900); font-size: 16px; }
.integration-card header p { margin: 5px 0 0; color: var(--gray-500); font-size: 12px; line-height: 1.5; }
.two-column-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.integration-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.test-message { margin: 10px 0 0; color: var(--gray-500); font-size: 12px; }
</style>
