<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Refresh, MagicStick, CircleCheck, Promotion, CopyDocument, Plus,
  Document, Loading, Checked, Coin
} from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import type { WeeklyReportFacts, WeeklyReportVO } from '@/types/weekly-report'

// ==================== 工具 ====================

const toDateStr = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

/** 任意日期 → 所在周周一 */
const toMonday = (value: string | Date) => {
  const date = value instanceof Date ? new Date(value) : new Date(value)
  const day = date.getDay() === 0 ? 7 : date.getDay()
  date.setDate(date.getDate() - (day - 1))
  return toDateStr(date)
}

const currentMonday = () => toMonday(new Date())

const fmtWan = (value: number | null | undefined) =>
  value === null || value === undefined ? '—' : (value / 10000).toFixed(1)

const shortRange = (row: WeeklyReportVO) =>
  `${row.weekStartDate.slice(5).replace('-', '.')} – ${row.periodEndDate.slice(5).replace('-', '.')}`

// ==================== 列表 ====================

const list = ref<WeeklyReportVO[]>([])
const listLoading = ref(false)

const loadList = async () => {
  listLoading.value = true
  try {
    list.value = await api.getWeeklyHistory()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    listLoading.value = false
  }
}

onMounted(loadList)

const summary = computed(() => {
  const total = list.value.length
  const inFlight = list.value.filter(r => ['PENDING', 'GENERATING', 'DRAFT'].includes(r.status)).length
  const confirmed = list.value.filter(r => ['CONFIRMED', 'PUBLISHED'].includes(r.status)).length
  const sheetPending = list.value.filter(r => r.yuqueDocUrl && r.sheetSyncStatus !== 'MANUAL_DONE').length
  return {
    total,
    inFlight,
    confirmed,
    sheetPending,
    confirmedRate: total ? Math.round((confirmed / total) * 100) : 100
  }
})

// ==================== 新建周报 ====================

const createVisible = ref(false)
const createWeek = ref(currentMonday())
const creating = ref(false)

const openCreate = () => {
  createWeek.value = currentMonday()
  createVisible.value = true
}

const createReport = async () => {
  creating.value = true
  try {
    const monday = toMonday(createWeek.value)
    const report = await api.getWeeklyReport(monday)
    createVisible.value = false
    await loadList()
    openEditor(report.weekStartDate)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '新建失败')
  } finally {
    creating.value = false
  }
}

// ==================== 编辑器抽屉 ====================

const editorVisible = ref(false)
const current = ref<WeeklyReportVO | null>(null)
const detailLoading = ref(false)
const facts = ref<WeeklyReportFacts | null>(null)
const factsLoading = ref(false)
const factsExpanded = ref(false)

const draft = ref({
  coreWork: '',
  kpiSection: '',
  risks: '',
  nextWeekPlan: '',
  minutesMarkdown: ''
})

const openEditor = async (weekStartDate: string) => {
  editorVisible.value = true
  detailLoading.value = true
  facts.value = null
  factsExpanded.value = false
  try {
    current.value = await api.getWeeklyReport(weekStartDate)
    draft.value = {
      coreWork: current.value.coreWork ?? '',
      kpiSection: current.value.kpiSection ?? '',
      risks: current.value.risks ?? '',
      nextWeekPlan: current.value.nextWeekPlan ?? '',
      minutesMarkdown: current.value.minutesMarkdown ?? ''
    }
    if (current.value.status === 'GENERATING') startPolling()
    loadFacts(weekStartDate)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    detailLoading.value = false
  }
}

const closeEditor = () => {
  stopPolling()
  editorVisible.value = false
  loadList()
}

const loadFacts = async (weekStartDate: string) => {
  factsLoading.value = true
  try {
    facts.value = await api.getWeeklyFacts(weekStartDate)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '事实采集失败')
  } finally {
    factsLoading.value = false
  }
}

// ==================== 生成轮询 ====================

let pollTimer: ReturnType<typeof setInterval> | null = null

const startPolling = () => {
  stopPolling()
  pollTimer = setInterval(async () => {
    if (!current.value) return
    const latest = await api.getWeeklyReport(current.value.weekStartDate)
    current.value = latest
    if (latest.status !== 'GENERATING') {
      stopPolling()
      if (latest.status === 'DRAFT') {
        ElMessage.success('周报草稿已生成')
        await openEditor(latest.weekStartDate)
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

// ==================== 编辑器操作 ====================

const savingInputs = ref(false)
const saveInputs = async () => {
  if (!current.value) return
  savingInputs.value = true
  try {
    current.value = await api.saveWeeklyInputs(current.value.id, {
      wecomSummary: current.value.wecomSummary ?? '',
      manualNotes: current.value.manualNotes ?? ''
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
  if (!current.value) return
  generating.value = true
  try {
    current.value = await api.generateWeeklyReport(current.value.id)
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
  if (!current.value) return
  savingContent.value = true
  try {
    current.value = await api.saveWeeklyContent(current.value.id, draft.value)
    ElMessage.success('内容已保存')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    savingContent.value = false
  }
}

const confirming = ref(false)
const confirmReport = async () => {
  if (!current.value) return
  confirming.value = true
  try {
    current.value = await api.confirmWeeklyReport(current.value.id)
    ElMessage.success('周报已确认')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '确认失败')
  } finally {
    confirming.value = false
  }
}

const copyReport = async () => {
  if (!current.value) return
  const sections = [
    ['本周核心工作完成情况', current.value.coreWork],
    ['KPI相关情况', current.value.kpiSection],
    ['问题/风险与解决办法', current.value.risks],
    ['下周工作计划', current.value.nextWeekPlan]
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

// ==================== 发布与同步抽屉 ====================

const publishVisible = ref(false)
const publishTarget = ref<WeeklyReportVO | null>(null)

const openPublish = async (row: WeeklyReportVO) => {
  publishVisible.value = true
  publishTarget.value = row
  try {
    publishTarget.value = await api.getWeeklyReport(row.weekStartDate)
  } catch {
    // 保留行数据
  }
}

const publishing = ref(false)
const publishYuque = async () => {
  if (!publishTarget.value) return
  publishing.value = true
  try {
    publishTarget.value = await api.publishWeeklyYuque(publishTarget.value.id)
    ElMessage.success('已发布语雀')
    loadList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发布失败')
  } finally {
    publishing.value = false
  }
}

const markingSheet = ref(false)
const markSheet = async () => {
  if (!publishTarget.value) return
  const info = publishTarget.value.sheetTargetInfo
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
    publishTarget.value = await api.publishWeeklySheet(publishTarget.value.id)
    ElMessage.success('已标记回填完成')
    loadList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '标记失败')
  } finally {
    markingSheet.value = false
  }
}

const pushing = ref(false)
const pushWecom = async () => {
  if (!publishTarget.value) return
  pushing.value = true
  try {
    publishTarget.value = await api.pushWeeklyWecom(publishTarget.value.id)
    ElMessage.success('已推送企微')
    loadList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '推送失败')
  } finally {
    pushing.value = false
  }
}

// ==================== 展示 ====================

interface StatusMeta { label: string; tone: string }

const statusMeta: Record<string, StatusMeta> = {
  PENDING: { label: '待生成', tone: 'not-started' },
  GENERATING: { label: '生成中', tone: 'progressing' },
  DRAFT: { label: '草稿', tone: 'risk' },
  CONFIRMED: { label: '已确认', tone: 'completed' },
  PUBLISHED: { label: '已发布', tone: 'paused' },
  GENERATION_FAILED: { label: '生成失败', tone: 'blocked' }
}

const statusOf = (status: string | undefined) => statusMeta[status ?? 'PENDING'] ?? statusMeta.PENDING

const editable = computed(() => current.value?.editable ?? true)
</script>

<template>
  <div class="weekly-report-page">
    <!-- 操作栏：标题 + 概览 + 动作 -->
    <section class="page-toolbar" aria-label="周报操作栏">
      <div class="register-titlebar">
        <h1>周报中心</h1>
        <p>BG 周报与周会纪要 · 每周五 17:00 自动生成草稿</p>
      </div>
      <section class="summary-strip toolbar-summary" aria-label="周报概览">
        <div class="summary-cell all">
          <div class="summary-label"><span><el-icon><Document /></el-icon></span>全部周报</div>
          <div class="summary-value"><strong>{{ summary.total }}</strong><small>周</small></div>
          <div class="summary-meter"><i :style="{ width: '100%' }" /></div>
        </div>
        <div class="summary-cell progressing">
          <div class="summary-label"><span><el-icon><Loading /></el-icon></span>进行中</div>
          <div class="summary-value"><strong>{{ summary.inFlight }}</strong><small>待办</small></div>
          <div class="summary-meter"><i :style="{ width: `${summary.total ? summary.inFlight / summary.total * 100 : 0}%` }" /></div>
        </div>
        <div class="summary-cell confirmed">
          <div class="summary-label"><span><el-icon><Checked /></el-icon></span>已确认/发布</div>
          <div class="summary-value"><strong>{{ summary.confirmed }}</strong><small>{{ summary.confirmedRate }}%</small></div>
          <div class="summary-meter"><i :style="{ width: `${summary.confirmedRate}%` }" /></div>
        </div>
        <div class="summary-cell pending">
          <div class="summary-label"><span><el-icon><Coin /></el-icon></span>待回填汇总表</div>
          <div class="summary-value"><strong>{{ summary.sheetPending }}</strong><small>周</small></div>
          <div class="summary-meter"><i :style="{ width: `${summary.confirmed ? summary.sheetPending / summary.confirmed * 100 : 0}%` }" /></div>
        </div>
      </section>
      <div class="toolbar-actions">
        <el-button :icon="Refresh" aria-label="刷新" :loading="listLoading" @click="loadList" />
        <el-button type="primary" :icon="Plus" @click="openCreate">新建周报</el-button>
      </div>
    </section>

    <!-- 记录列表 -->
    <section class="table-panel" aria-label="周报记录">
      <el-table
        :data="list"
        v-loading="listLoading"
        row-key="id"
        class="report-table"
        empty-text="暂无周报记录，点击右上角「新建周报」开始"
        @row-click="(row: WeeklyReportVO) => openEditor(row.weekStartDate)"
      >
        <el-table-column label="周" min-width="200">
          <template #default="{ row }">
            <div class="matter-title">{{ shortRange(row) }}</div>
            <div class="matter-subline">
              {{ row.weekStartDate.slice(0, 4) }} 年 · {{ row.generationMode ? `${row.generationMode} · ${row.generationModel ?? ''}` : '未生成' }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag class="list-status-tag" :class="`status-${statusOf(row.status).tone}`" effect="light">
              {{ statusOf(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="同步状态" min-width="230">
          <template #default="{ row }">
            <div class="sync-pills">
              <el-link v-if="row.yuqueDocUrl" :href="row.yuqueDocUrl" target="_blank" class="sync-pill done" @click.stop>
                语雀已发布
              </el-link>
              <span v-else class="sync-pill">语雀未发布</span>
              <span class="sync-pill" :class="{ done: row.sheetSyncStatus === 'MANUAL_DONE' }">
                汇总表{{ row.sheetSyncStatus === 'MANUAL_DONE' ? '已回填' : '待回填' }}
              </span>
              <span class="sync-pill" :class="{ done: row.wecomPushStatus === 'SUCCESS' }">
                企微{{ row.wecomPushStatus === 'SUCCESS' ? '已推送' : '未推送' }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="150">
          <template #default="{ row }">
            <span class="matter-subline">{{ row.updatedAt?.replace('T', ' ').slice(0, 16) ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <div class="row-actions" @click.stop>
              <el-button size="small" link type="primary" @click="openEditor(row.weekStartDate)">
                {{ row.editable ? '编辑' : '查看' }}
              </el-button>
              <el-button size="small" link type="primary" @click="openPublish(row)">同步</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 新建周报 -->
    <el-dialog v-model="createVisible" title="新建周报" width="420px">
      <div class="create-body">
        <span class="create-label">选择周</span>
        <el-date-picker
          v-model="createWeek"
          type="week"
          placeholder="选择周"
          :clearable="false"
          :first-day-of-week="1"
          format="YYYY 第 ww 周"
          value-format="YYYY-MM-DD"
          style="width: 200px"
        />
      </div>
      <div class="create-hint">已存在的周将直接打开对应周报。</div>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createReport">确定</el-button>
      </template>
    </el-dialog>

    <!-- 编辑器抽屉 -->
    <el-drawer v-model="editorVisible" size="75%" :with-header="false" @close="closeEditor">
      <div class="editor-wrap" v-loading="detailLoading">
        <section v-if="current" class="editor-toolbar">
          <div class="register-titlebar">
            <h1>
              {{ shortRange(current) }} 周报
              <el-tag class="list-status-tag" :class="`status-${statusOf(current.status).tone}`" effect="light">
                {{ statusOf(current.status).label }}
              </el-tag>
            </h1>
            <p>{{ current.weekStartDate }} ~ {{ current.periodEndDate }}<template v-if="current.generationModel"> · {{ current.generationModel }}</template></p>
          </div>
          <div class="toolbar-actions">
            <el-button
              type="primary"
              :icon="MagicStick"
              :loading="generating || current.status === 'GENERATING'"
              :disabled="!editable"
              @click="generate"
            >生成 / 重新生成</el-button>
            <el-button :loading="savingContent" :disabled="!editable" @click="saveContent">保存</el-button>
            <el-button
              type="success"
              :icon="CircleCheck"
              :loading="confirming"
              :disabled="!editable || current.status === 'CONFIRMED'"
              @click="confirmReport"
            >确认</el-button>
            <el-button :icon="CopyDocument" aria-label="复制全文" @click="copyReport" />
          </div>
        </section>

        <template v-if="current">
          <el-alert
            v-if="current.status === 'GENERATION_FAILED' && current.generationError"
            class="load-error"
            type="error"
            :title="current.generationError"
            show-icon
            :closable="false"
          />

          <!-- 事实概览 -->
          <section class="editor-section" v-loading="factsLoading" aria-label="自动采集事实">
            <header class="section-header">
              <div>
                <span>自动采集事实</span>
                <small v-if="facts">大事儿 {{ facts.keyMatters.length }} 项 · {{ facts.finance.month }}</small>
              </div>
              <button v-if="facts" type="button" class="section-toggle" @click="factsExpanded = !factsExpanded">
                {{ factsExpanded ? '收起明细' : '展开明细' }}
              </button>
            </header>
            <section v-if="facts" class="summary-strip facts-strip">
              <div class="summary-cell all">
                <div class="summary-label"><span><el-icon><Document /></el-icon></span>大事儿跟踪</div>
                <div class="summary-value"><strong>{{ facts.keyMatters.length }}</strong><small>项</small></div>
                <div class="summary-meter"><i :style="{ width: '100%' }" /></div>
              </div>
              <div class="summary-cell progressing">
                <div class="summary-label"><span><el-icon><Coin /></el-icon></span>新增合同</div>
                <div class="summary-value"><strong>{{ fmtWan(facts.finance.newContractAmount) }}</strong><small>万元</small></div>
                <div class="summary-meter"><i :style="{ width: '100%' }" /></div>
              </div>
              <div class="summary-cell confirmed">
                <div class="summary-label"><span><el-icon><Checked /></el-icon></span>交付口径</div>
                <div class="summary-value"><strong>{{ fmtWan(facts.finance.deliveredAmount) }}</strong><small>万元</small></div>
                <div class="summary-meter"><i :style="{ width: '100%' }" /></div>
              </div>
              <div class="summary-cell pending">
                <div class="summary-label"><span><el-icon><Coin /></el-icon></span>累计应收</div>
                <div class="summary-value"><strong>{{ fmtWan(facts.finance.cumulativeReceivable) }}</strong><small>万元</small></div>
                <div class="summary-meter"><i :style="{ width: '100%' }" /></div>
              </div>
            </section>
            <template v-if="facts && factsExpanded">
              <el-table :data="facts.keyMatters" size="small" max-height="240" class="facts-table">
                <el-table-column prop="title" label="事项" min-width="180" show-overflow-tooltip />
                <el-table-column prop="ownerName" label="负责人" width="90" />
                <el-table-column prop="status" label="状态" width="90" />
                <el-table-column prop="progress" label="进度" width="70">
                  <template #default="{ row }">{{ row.progress ?? '—' }}%</template>
                </el-table-column>
              </el-table>
              <div v-if="facts.lastWeekReport.exists" class="last-week">
                <div class="fact-title">上周计划（{{ facts.lastWeekReport.weekStart }} · {{ statusOf(facts.lastWeekReport.status).label }}）</div>
                <pre class="fact-text">{{ facts.lastWeekReport.nextWeekPlan || '（无）' }}</pre>
              </div>
            </template>
            <el-empty v-if="!facts && !factsLoading" description="本周尚无数据" :image-size="60" />
          </section>

          <!-- 人工输入 -->
          <section class="editor-section" aria-label="人工输入">
            <header class="section-header">
              <div>
                <span>人工输入</span>
                <small>企微智能总结与补充信息，生成前保存</small>
              </div>
              <el-button size="small" :loading="savingInputs" :disabled="!editable" @click="saveInputs">保存输入</el-button>
            </header>
            <el-input
              v-model="current.wecomSummary"
              type="textarea"
              :rows="4"
              placeholder="粘贴企微智能总结…"
              :disabled="!editable"
              class="input-block"
            />
            <el-input
              v-model="current.manualNotes"
              type="textarea"
              :rows="3"
              placeholder="人为补充信息（可选）…"
              :disabled="!editable"
            />
          </section>

          <!-- 周报四段 -->
          <section class="editor-section" aria-label="周报四段">
            <header class="section-header">
              <div>
                <span>周报（四段）</span>
                <small>纯文本，可直接复制提交企微汇报</small>
              </div>
            </header>
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
          </section>

          <!-- 周会纪要 -->
          <section class="editor-section" aria-label="周会纪要">
            <header class="section-header">
              <div>
                <span>周会纪要</span>
                <small>Markdown，发布至语雀部门会议目录</small>
              </div>
            </header>
            <el-input v-model="draft.minutesMarkdown" type="textarea" :rows="16" :disabled="!editable" />
          </section>
        </template>
      </div>
    </el-drawer>

    <!-- 发布与同步抽屉 -->
    <el-drawer v-model="publishVisible" size="440px" :with-header="false">
      <div class="editor-wrap" v-if="publishTarget">
        <section class="editor-toolbar">
          <div class="register-titlebar">
            <h1>发布与同步</h1>
            <p>{{ publishTarget.weekStartDate }} ~ {{ publishTarget.periodEndDate }}</p>
          </div>
        </section>

        <section class="editor-section">
          <header class="section-header">
            <div>
              <span>语雀发布</span>
              <small>周会纪要发布至部门会议目录</small>
            </div>
            <span class="sync-pill" :class="{ done: publishTarget.yuqueTocStatus === 'VERIFIED' }">
              {{ publishTarget.yuqueTocStatus === 'VERIFIED' ? '已挂载验证' : publishTarget.yuqueTocStatus === 'MOVED' ? '待人工挂目录' : publishTarget.yuqueTocStatus === 'NOT_FOUND' ? '未找到节点' : '未发布' }}
            </span>
          </header>
          <div v-if="publishTarget.yuqueDocUrl" class="publish-row">
            <el-link :href="publishTarget.yuqueDocUrl" target="_blank" type="primary">{{ publishTarget.yuqueDocUrl }}</el-link>
          </div>
          <el-button
            type="primary"
            size="small"
            :loading="publishing"
            :disabled="!publishTarget.minutesMarkdown"
            @click="publishYuque"
          >发布语雀</el-button>
        </section>

        <section class="editor-section">
          <header class="section-header">
            <div>
              <span>汇总表回填</span>
              <small>{{ publishTarget.sheetTargetInfo?.dateRangeLabel }} · {{ publishTarget.sheetTargetInfo?.teamName }} · K 列</small>
            </div>
            <span class="sync-pill" :class="{ done: publishTarget.sheetSyncStatus === 'MANUAL_DONE' }">
              {{ publishTarget.sheetSyncStatus === 'MANUAL_DONE' ? '已回填' : '待回填' }}
            </span>
          </header>
          <div class="publish-row">
            <el-link :href="publishTarget.sheetTargetInfo?.sheetUrl" target="_blank" type="primary">
              打开「{{ publishTarget.sheetTargetInfo?.sheetName }}」汇总表
            </el-link>
          </div>
          <el-button size="small" :loading="markingSheet" :disabled="!publishTarget.yuqueDocUrl" @click="markSheet">
            标记已回填
          </el-button>
        </section>

        <section class="editor-section">
          <header class="section-header">
            <div>
              <span>企微推送</span>
              <small>推送终稿内容提醒，配合一键复制提交</small>
            </div>
            <span class="sync-pill" :class="{ done: publishTarget.wecomPushStatus === 'SUCCESS' }">
              {{ publishTarget.wecomPushStatus === 'SUCCESS' ? `已推送 ${publishTarget.wecomPushedAt?.slice(5, 16) ?? ''}` : '未推送' }}
            </span>
          </header>
          <el-button
            size="small"
            type="warning"
            :icon="Promotion"
            :loading="pushing"
            :disabled="publishTarget.status !== 'CONFIRMED' && publishTarget.status !== 'PUBLISHED'"
            @click="pushWecom"
          >推送企微</el-button>
        </section>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
/* 与大事儿管理一致的冷白驾驶舱设计语言 */
.weekly-report-page {
  --km-primary: #4f5cf7;
  --km-primary-strong: #315efb;
  --km-indigo-soft: #eef2ff;
  --km-surface: #ffffff;
  --km-border: #e2e8f0;
  --km-ink: #1e293b;
  --km-muted: #64748b;
  --km-success: #10b981;
  --km-success-soft: #ecfdf5;
  --km-warning: #f59e0b;
  --km-warning-soft: #fffbeb;
  --km-danger: #ef4444;
  --km-danger-soft: #fff1f2;
  --km-radius-md: 12px;
  width: 100%;
  min-width: 0;
  color: var(--km-ink);
}

.weekly-report-page :deep(.el-button--primary) {
  --el-button-bg-color: var(--km-primary-strong);
  --el-button-border-color: var(--km-primary-strong);
  --el-button-hover-bg-color: #244fe4;
  --el-button-hover-border-color: #244fe4;
  box-shadow: 0 8px 18px rgb(49 94 251 / 20%);
}

/* ==================== 操作栏 ==================== */

.page-toolbar {
  display: grid;
  grid-template-columns: max-content minmax(440px, 1fr) max-content;
  align-items: center;
  gap: 18px;
  min-height: 46px;
  margin-bottom: 20px;
}

.register-titlebar {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.register-titlebar h1 {
  margin: 0;
  color: #0f172a;
  font-size: 22px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.register-titlebar p {
  margin: 0;
  color: var(--km-muted);
  font-size: 13px;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.toolbar-actions .el-button + .el-button {
  margin-left: 0;
}

/* ==================== 概览条 ==================== */

.summary-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.toolbar-summary {
  min-width: 0;
  margin: 0;
  gap: 0;
  overflow: hidden;
  border: 1px solid var(--km-border);
  border-radius: 10px;
  background: var(--km-surface);
  box-shadow: 0 3px 12px rgb(15 23 42 / 4%);
}

.summary-cell {
  min-height: 104px;
  display: grid;
  grid-template-columns: 1fr;
  align-content: space-between;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--km-border);
  border-radius: var(--km-radius-md);
  background: var(--km-surface);
  box-shadow: 0 4px 14px rgb(15 23 42 / 4%);
}

.toolbar-summary .summary-cell {
  min-width: 0;
  min-height: 58px;
  grid-template-columns: minmax(0, 1fr) auto;
  grid-template-rows: auto 3px;
  align-content: center;
  gap: 7px 8px;
  padding: 8px 10px;
  border: 0;
  border-right: 1px solid var(--km-border);
  border-radius: 0;
  box-shadow: none;
}

.toolbar-summary .summary-cell:last-child {
  border-right: 0;
}

.summary-label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--km-muted);
  font-size: 13px;
  font-weight: 650;
}

.summary-label > span {
  width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  color: var(--km-primary);
  background: var(--km-indigo-soft);
}

.toolbar-summary .summary-label {
  gap: 6px;
  font-size: 11px;
  white-space: nowrap;
}

.toolbar-summary .summary-label > span {
  width: 24px;
  height: 24px;
  border-radius: 7px;
}

.summary-cell.progressing .summary-label > span { color: #2563eb; background: #eff6ff; }
.summary-cell.confirmed .summary-label > span { color: var(--km-success); background: var(--km-success-soft); }
.summary-cell.pending .summary-label > span { color: var(--km-warning); background: var(--km-warning-soft); }

.summary-value {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 8px;
}

.toolbar-summary .summary-value {
  align-items: baseline;
  justify-content: flex-end;
  gap: 5px;
  white-space: nowrap;
}

.summary-value strong {
  color: var(--km-ink);
  font-size: 25px;
  line-height: 1;
}

.toolbar-summary .summary-value strong {
  font-size: 19px;
}

.summary-value small {
  color: var(--km-muted);
  font-size: 11px;
}

.toolbar-summary .summary-value small {
  font-size: 9px;
}

.summary-meter {
  height: 4px;
  overflow: hidden;
  border-radius: 999px;
  background: #eef2f7;
}

.toolbar-summary .summary-meter {
  grid-column: 1 / -1;
  height: 3px;
}

.summary-meter i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #6366f1, #4f5cf7);
}

.summary-cell.progressing .summary-meter i { background: #3b82f6; }
.summary-cell.confirmed .summary-meter i { background: var(--km-success); }
.summary-cell.pending .summary-meter i { background: var(--km-warning); }

/* ==================== 记录表 ==================== */

.table-panel {
  border: 1px solid var(--km-border);
  border-radius: var(--km-radius-md);
  background: var(--km-surface);
  box-shadow: 0 4px 14px rgb(15 23 42 / 4%);
  overflow: hidden;
}

.report-table {
  width: 100%;
}

.report-table :deep(.el-table__row) {
  cursor: pointer;
}

.matter-title {
  font-weight: 600;
  color: var(--km-ink);
  line-height: 1.4;
}

.matter-subline {
  margin-top: 4px;
  font-size: 12px;
  color: var(--km-muted);
}

.list-status-tag.status-not-started { color: #64748b; background: #f1f5f9; border-color: #cbd5e1; }
.list-status-tag.status-progressing { color: #2563eb; background: #eff6ff; border-color: #bfdbfe; }
.list-status-tag.status-risk { color: #d97706; background: #fffbeb; border-color: #fde68a; }
.list-status-tag.status-blocked { color: #dc2626; background: #fef2f2; border-color: #fecaca; }
.list-status-tag.status-completed { color: #059669; background: #ecfdf5; border-color: #a7f3d0; }
.list-status-tag.status-paused { color: #7c3aed; background: #f5f3ff; border-color: #ddd6fe; }

.sync-pills {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.sync-pill {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  color: var(--km-muted);
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  text-decoration: none;
}

.sync-pill.done {
  color: #059669;
  background: var(--km-success-soft);
  border-color: #a7f3d0;
}

.row-actions {
  display: flex;
  gap: 4px;
  align-items: center;
}

.row-actions .el-button + .el-button {
  margin-left: 0;
}

.list-status-tag {
  min-width: 64px;
  text-align: center;
}

.load-error {
  margin-bottom: 12px;
}

/* ==================== 抽屉与区块 ==================== */

.editor-wrap {
  padding: 4px 8px 16px;
}

.editor-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 18px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.editor-section {
  margin-bottom: 14px;
  padding: 14px 16px;
  border: 1px solid var(--km-border);
  border-radius: var(--km-radius-md);
  background: var(--km-surface);
  box-shadow: 0 4px 14px rgb(15 23 42 / 4%);
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.section-header > div {
  display: grid;
  gap: 2px;
}

.section-header span {
  font-weight: 650;
  color: var(--km-ink);
  font-size: 14px;
}

.section-header small {
  color: var(--km-muted);
  font-size: 12px;
}

.section-toggle {
  border: 0;
  background: transparent;
  color: var(--km-primary-strong);
  font-size: 12px;
  cursor: pointer;
}

.facts-strip {
  margin-bottom: 4px;
}

.facts-table {
  margin-top: 10px;
}

.last-week {
  margin-top: 10px;
}

.fact-title {
  font-weight: 600;
  margin-bottom: 6px;
  font-size: 13px;
  color: var(--km-ink);
}

.fact-text {
  margin: 0;
  white-space: pre-wrap;
  font-size: 12px;
  color: var(--km-ink);
  background: #f8fafc;
  border: 1px solid var(--km-border);
  padding: 8px 10px;
  border-radius: 8px;
  max-height: 140px;
  overflow: auto;
}

.input-block {
  margin-bottom: 8px;
}

.output-block {
  margin-bottom: 12px;
}

.output-block:last-child {
  margin-bottom: 0;
}

.output-label {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 6px;
  color: var(--km-ink);
}

.publish-row {
  margin-bottom: 10px;
  font-size: 13px;
  word-break: break-all;
}

/* ==================== 新建对话框 ==================== */

.create-body {
  display: flex;
  align-items: center;
  gap: 12px;
}

.create-label {
  color: var(--km-muted);
  font-size: 13px;
}

.create-hint {
  margin-top: 8px;
  color: #94a3b8;
  font-size: 12px;
}

/* ==================== 响应式 ==================== */

@media (max-width: 1200px) {
  .page-toolbar {
    grid-template-columns: 1fr;
    align-items: stretch;
  }
  .toolbar-actions {
    justify-content: flex-end;
  }
}
</style>
