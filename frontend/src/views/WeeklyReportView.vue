<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, MagicStick, CircleCheck, Promotion, CopyDocument, Clock } from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import type { WeeklyReportFacts, WeeklyReportVO } from '@/types/weekly-report'

// ==================== 状态 ====================

const currentMonday = () => {
  const now = new Date()
  const day = now.getDay() === 0 ? 7 : now.getDay()
  const monday = new Date(now)
  monday.setDate(now.getDate() - (day - 1))
  return toDateStr(monday)
}

const toDateStr = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

const weekStart = ref(currentMonday())
const loading = ref(false)
const report = ref<WeeklyReportVO | null>(null)
const facts = ref<WeeklyReportFacts | null>(null)
const factsLoading = ref(false)
const history = ref<WeeklyReportVO[]>([])
const historyVisible = ref(false)
const publishVisible = ref(false)

// 编辑草稿（保存前不写入 report）
const draft = ref({
  coreWork: '',
  kpiSection: '',
  risks: '',
  nextWeekPlan: '',
  minutesMarkdown: ''
})

// ==================== 数据加载 ====================

const loadReport = async () => {
  loading.value = true
  try {
    report.value = await api.getWeeklyReport(weekStart.value)
    draft.value = {
      coreWork: report.value.coreWork ?? '',
      kpiSection: report.value.kpiSection ?? '',
      risks: report.value.risks ?? '',
      nextWeekPlan: report.value.nextWeekPlan ?? '',
      minutesMarkdown: report.value.minutesMarkdown ?? ''
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

const loadFacts = async () => {
  factsLoading.value = true
  try {
    facts.value = await api.getWeeklyFacts(weekStart.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '事实采集失败')
  } finally {
    factsLoading.value = false
  }
}

const loadHistory = async () => {
  try {
    history.value = await api.getWeeklyHistory()
  } catch {
    // 历史加载失败不阻塞主流程
  }
}

/** 周选择器（type=week，首日周一）→ 统一归一到本周一 */
const onWeekChange = (value: string | Date | null) => {
  if (!value) return
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return
  const day = date.getDay() === 0 ? 7 : date.getDay()
  date.setDate(date.getDate() - (day - 1))
  const monday = toDateStr(date)
  if (monday === weekStart.value) return
  weekStart.value = monday
  stopPolling()
  loadReport()
  loadFacts()
}

onMounted(() => {
  loadReport()
  loadFacts()
  loadHistory()
})

// ==================== 生成轮询 ====================

let pollTimer: ReturnType<typeof setInterval> | null = null

const startPolling = () => {
  stopPolling()
  pollTimer = setInterval(async () => {
    if (!report.value) return
    const latest = await api.getWeeklyReport(weekStart.value)
    report.value = latest
    if (latest.status !== 'GENERATING') {
      stopPolling()
      if (latest.status === 'DRAFT') {
        ElMessage.success('周报草稿已生成')
        await loadReport()
      } else if (latest.status === 'GENERATION_FAILED') {
        ElMessage.error('生成失败：' + (latest.generationError ?? '未知错误'))
      }
    }
  }, 3000)
}

const stopPolling = () => {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

onUnmounted(stopPolling)

// ==================== 操作 ====================

const savingInputs = ref(false)
const saveInputs = async () => {
  if (!report.value) return
  savingInputs.value = true
  try {
    report.value = await api.saveWeeklyInputs(report.value.id, {
      wecomSummary: report.value.wecomSummary ?? '',
      manualNotes: report.value.manualNotes ?? ''
    })
    ElMessage.success('输入已保存')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    savingInputs.value = false
  }
}

const generating = ref(false)
const generate = async () => {
  if (!report.value) return
  generating.value = true
  try {
    const result = await api.generateWeeklyReport(report.value.id)
    report.value = result
    ElMessage.info('AI 生成中，约需 30-60 秒…')
    startPolling()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '触发生成失败')
  } finally {
    generating.value = false
  }
}

const savingContent = ref(false)
const saveContent = async () => {
  if (!report.value) return
  savingContent.value = true
  try {
    report.value = await api.saveWeeklyContent(report.value.id, draft.value)
    ElMessage.success('内容已保存')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    savingContent.value = false
  }
}

const confirming = ref(false)
const confirmReport = async () => {
  if (!report.value) return
  confirming.value = true
  try {
    report.value = await api.confirmWeeklyReport(report.value.id)
    ElMessage.success('周报已确认')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '确认失败')
  } finally {
    confirming.value = false
  }
}

const publishing = ref(false)
const publishYuque = async () => {
  if (!report.value) return
  publishing.value = true
  try {
    report.value = await api.publishWeeklyYuque(report.value.id)
    ElMessage.success('已发布语雀')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发布失败')
  } finally {
    publishing.value = false
  }
}

const markingSheet = ref(false)
const markSheet = async () => {
  if (!report.value) return
  const info = report.value.sheetTargetInfo
  try {
    await ElMessageBox.confirm(
      `请在汇总表「${info?.sheetName ?? ''}」中找到 ${info?.dateRangeLabel ?? ''} 行、` +
      `${info?.teamName ?? ''} 团队，将周会纪要链接粘贴至 K 列后确认。`,
      '人工回填汇总表',
      { confirmButtonText: '已回填', cancelButtonText: '取消', type: 'info' }
    )
  } catch {
    return
  }
  markingSheet.value = true
  try {
    report.value = await api.publishWeeklySheet(report.value.id)
    ElMessage.success('已标记回填完成')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '标记失败')
  } finally {
    markingSheet.value = false
  }
}

const pushing = ref(false)
const pushWecom = async () => {
  if (!report.value) return
  pushing.value = true
  try {
    report.value = await api.pushWeeklyWecom(report.value.id)
    ElMessage.success('已推送企微')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '推送失败')
  } finally {
    pushing.value = false
  }
}

const copyReport = async () => {
  if (!report.value) return
  const sections = [
    ['本周核心工作完成情况', report.value.coreWork],
    ['KPI相关情况', report.value.kpiSection],
    ['问题/风险与解决办法', report.value.risks],
    ['下周工作计划', report.value.nextWeekPlan]
  ] as const
  const text = sections
    .filter(([, body]) => body && body.trim())
    .map(([title, body]) => `■ ${title}\n${body!.trim()}`)
    .join('\n\n')
  if (!text) {
    ElMessage.warning('周报内容为空')
    return
  }
  await navigator.clipboard.writeText(text)
  ElMessage.success('周报全文已复制')
}

const openHistory = () => {
  loadHistory()
  historyVisible.value = true
}

const viewHistory = (row: WeeklyReportVO) => {
  historyVisible.value = false
  onWeekChange(row.weekStartDate)
}

// ==================== 展示 ====================

const statusMeta: Record<string, { label: string; type: 'info' | 'primary' | 'success' | 'warning' | 'danger' }> = {
  PENDING: { label: '待生成', type: 'info' },
  GENERATING: { label: '生成中', type: 'primary' },
  DRAFT: { label: '草稿', type: 'warning' },
  CONFIRMED: { label: '已确认', type: 'success' },
  PUBLISHED: { label: '已发布', type: 'success' },
  GENERATION_FAILED: { label: '生成失败', type: 'danger' }
}

const statusLabel = computed(() => statusMeta[report.value?.status ?? 'PENDING']?.label ?? '待生成')
const statusType = computed(() => statusMeta[report.value?.status ?? 'PENDING']?.type ?? 'info')

const editable = computed(() => report.value?.editable ?? true)

const fmtWan = (value: number | null | undefined) =>
  value === null || value === undefined ? '—' : (value / 10000).toFixed(1) + ' 万'
</script>

<template>
  <div class="weekly-report-page" v-loading="loading">
    <div class="page-header">
      <h2>
        周报中心
        <el-tag :type="statusType" effect="light" class="status-tag">{{ statusLabel }}</el-tag>
        <span class="period-label" v-if="report">{{ report.weekStartDate }} ~ {{ report.periodEndDate }}</span>
      </h2>
      <div class="header-actions">
        <el-date-picker
          :model-value="weekStart"
          type="week"
          placeholder="选择周"
          :clearable="false"
          :first-day-of-week="1"
          format="YYYY 第 ww 周"
          value-format="YYYY-MM-DD"
          style="width: 160px"
          @update:model-value="onWeekChange"
        />
        <el-button :icon="Refresh" :loading="loading" @click="loadReport(); loadFacts()">刷新</el-button>
        <el-button
          type="primary"
          :icon="MagicStick"
          :loading="generating || report?.status === 'GENERATING'"
          :disabled="!editable"
          @click="generate"
        >生成 / 重新生成</el-button>
        <el-button :loading="savingContent" :disabled="!editable" @click="saveContent">保存</el-button>
        <el-button
          type="success"
          :icon="CircleCheck"
          :loading="confirming"
          :disabled="!editable || report?.status === 'CONFIRMED'"
          @click="confirmReport"
        >确认</el-button>
        <el-button :icon="CopyDocument" @click="copyReport">复制全文</el-button>
        <el-button :icon="Promotion" @click="publishVisible = true">发布与同步</el-button>
        <el-button :icon="Clock" @click="openHistory">历史周报</el-button>
      </div>
    </div>

    <el-alert
      v-if="report?.status === 'GENERATION_FAILED' && report.generationError"
      type="error"
      :title="report.generationError"
      :closable="false"
      class="error-alert"
    />

    <!-- 人工输入 -->
    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-header">
          <span>人工输入</span>
          <el-button size="small" :loading="savingInputs" :disabled="!editable" @click="saveInputs">保存输入</el-button>
        </div>
      </template>
      <el-input
        v-model="report!.wecomSummary"
        type="textarea"
        :rows="4"
        placeholder="粘贴企微智能总结…"
        :disabled="!editable"
        class="input-block"
      />
      <el-input
        v-model="report!.manualNotes"
        type="textarea"
        :rows="3"
        placeholder="人为补充信息（可选）…"
        :disabled="!editable"
      />
    </el-card>

    <!-- 自动采集事实 -->
    <el-card shadow="never" class="section-card" v-loading="factsLoading">
      <template #header><span>自动采集事实</span></template>
      <div v-if="facts" class="facts-panel">
        <div class="fact-block">
          <div class="fact-title">大事儿进度（{{ facts.keyMatters.length }}）</div>
          <el-table :data="facts.keyMatters" size="small" max-height="260" border>
            <el-table-column prop="title" label="事项" min-width="180" show-overflow-tooltip />
            <el-table-column prop="ownerName" label="负责人" width="90" />
            <el-table-column prop="status" label="状态" width="90" />
            <el-table-column prop="progress" label="进度" width="70">
              <template #default="{ row }">{{ row.progress ?? '—' }}%</template>
            </el-table-column>
          </el-table>
        </div>
        <div class="fact-block finance-row">
          <span class="fact-title">财务（{{ facts.finance.month }}）</span>
          <el-tag size="small" effect="plain">新增合同 {{ fmtWan(facts.finance.newContractAmount) }}</el-tag>
          <el-tag size="small" effect="plain">交付口径 {{ fmtWan(facts.finance.deliveredAmount) }}</el-tag>
          <el-tag size="small" effect="plain">累计应收 {{ fmtWan(facts.finance.cumulativeReceivable) }}</el-tag>
        </div>
        <div class="fact-block" v-if="facts.lastWeekReport.exists">
          <div class="fact-title">上周计划（{{ facts.lastWeekReport.weekStart }} · {{ facts.lastWeekReport.status }}）</div>
          <pre class="fact-text">{{ facts.lastWeekReport.nextWeekPlan || '（无）' }}</pre>
        </div>
      </div>
      <el-empty v-else description="本周尚无数据" :image-size="60" />
    </el-card>

    <!-- 周报输出 -->
    <el-card shadow="never" class="section-card">
      <template #header><span>周报（四段）</span></template>
      <div class="output-block">
        <div class="output-label">本周核心工作完成情况</div>
        <el-input v-model="draft.coreWork" type="textarea" :rows="8" :disabled="!editable" />
      </div>
      <div class="output-block">
        <div class="output-label">KPI相关情况</div>
        <el-input v-model="draft.kpiSection" type="textarea" :rows="6" :disabled="!editable" />
      </div>
      <div class="output-block">
        <div class="output-label">问题/风险与解决办法</div>
        <el-input v-model="draft.risks" type="textarea" :rows="5" :disabled="!editable" />
      </div>
      <div class="output-block">
        <div class="output-label">下周工作计划</div>
        <el-input v-model="draft.nextWeekPlan" type="textarea" :rows="5" :disabled="!editable" />
      </div>
    </el-card>

    <!-- 周会纪要 -->
    <el-card shadow="never" class="section-card">
      <template #header><span>周会纪要（Markdown）</span></template>
      <el-input v-model="draft.minutesMarkdown" type="textarea" :rows="16" :disabled="!editable" />
    </el-card>

    <!-- 历史周报抽屉 -->
    <el-drawer v-model="historyVisible" title="历史周报" size="520px">
      <el-table :data="history" size="small" border>
        <el-table-column prop="weekStartDate" label="周" width="110" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusMeta[row.status]?.type ?? 'info'">
              {{ statusMeta[row.status]?.label ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="语雀" min-width="100">
          <template #default="{ row }">
            <el-link v-if="row.yuqueDocUrl" :href="row.yuqueDocUrl" target="_blank" type="primary">纪要链接</el-link>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="viewHistory(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="history.length === 0" description="暂无历史周报" :image-size="60" />
    </el-drawer>

    <!-- 发布与同步抽屉 -->
    <el-drawer v-model="publishVisible" title="发布与同步" size="440px">
      <div class="publish-block">
        <div class="publish-title">语雀发布</div>
        <div class="publish-row">
          <span class="publish-label">TOC 状态</span>
          <el-tag v-if="report?.yuqueTocStatus" size="small"
            :type="report.yuqueTocStatus === 'VERIFIED' ? 'success' : 'warning'">
            {{ report.yuqueTocStatus === 'VERIFIED' ? '已挂载验证' : report.yuqueTocStatus === 'MOVED' ? '已创建待人工挂目录' : '未找到节点' }}
          </el-tag>
          <span v-else class="publish-empty">未发布</span>
        </div>
        <div class="publish-row" v-if="report?.yuqueDocUrl">
          <el-link :href="report.yuqueDocUrl" target="_blank" type="primary">{{ report.yuqueDocUrl }}</el-link>
        </div>
        <el-button
          type="primary"
          size="small"
          :loading="publishing"
          :disabled="!report?.minutesMarkdown"
          @click="publishYuque"
        >发布语雀</el-button>
      </div>

      <el-divider />

      <div class="publish-block">
        <div class="publish-title">汇总表回填</div>
        <div class="publish-row">
          <span class="publish-label">目标行</span>
          <span>{{ report?.sheetTargetInfo?.dateRangeLabel }} · {{ report?.sheetTargetInfo?.teamName }}</span>
        </div>
        <div class="publish-row">
          <span class="publish-label">位置</span>
          <el-link :href="report?.sheetTargetInfo?.sheetUrl" target="_blank" type="primary">
            {{ report?.sheetTargetInfo?.sheetName }} 表 K 列
          </el-link>
        </div>
        <div class="publish-row">
          <span class="publish-label">状态</span>
          <el-tag v-if="report?.sheetSyncStatus === 'MANUAL_DONE'" size="small" type="success">已回填</el-tag>
          <span v-else class="publish-empty">待回填</span>
        </div>
        <el-button size="small" :loading="markingSheet" :disabled="!report?.yuqueDocUrl" @click="markSheet">
          标记已回填
        </el-button>
      </div>

      <el-divider />

      <div class="publish-block">
        <div class="publish-title">企微推送</div>
        <div class="publish-row">
          <span class="publish-label">状态</span>
          <el-tag v-if="report?.wecomPushStatus === 'SUCCESS'" size="small" type="success">
            已推送 {{ report.wecomPushedAt?.slice(0, 16) ?? '' }}
          </el-tag>
          <span v-else class="publish-empty">未推送</span>
        </div>
        <el-button
          size="small"
          type="warning"
          :icon="Promotion"
          :loading="pushing"
          :disabled="report?.status !== 'CONFIRMED' && report?.status !== 'PUBLISHED'"
          @click="pushWecom"
        >推送企微</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.weekly-report-page {
  padding: 16px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
  gap: 8px;
}

.page-header h2 {
  margin: 0;
  display: flex;
  align-items: center;
  gap: 10px;
}

.status-tag {
  font-weight: 400;
}

.period-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  font-weight: 400;
}

.header-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.error-alert {
  margin-bottom: 12px;
}

.section-card {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.input-block {
  margin-bottom: 8px;
}

.facts-panel .fact-block {
  margin-bottom: 12px;
}

.fact-title {
  font-weight: 600;
  margin-bottom: 6px;
  font-size: 13px;
}

.fact-text {
  margin: 0;
  white-space: pre-wrap;
  font-size: 12px;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  padding: 8px;
  border-radius: 4px;
  max-height: 140px;
  overflow: auto;
}

.finance-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.finance-row .fact-title {
  margin-bottom: 0;
}

.output-block {
  margin-bottom: 12px;
}

.output-label {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 4px;
}

.publish-block .publish-title {
  font-weight: 600;
  margin-bottom: 8px;
}

.publish-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 13px;
}

.publish-label {
  color: var(--el-text-color-secondary);
  min-width: 52px;
}

.publish-empty {
  color: var(--el-text-color-placeholder);
}
</style>
