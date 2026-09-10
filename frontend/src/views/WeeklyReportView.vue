<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, MagicStick, CircleCheck, Promotion, CopyDocument, Plus } from '@element-plus/icons-vue'
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
  value === null || value === undefined ? '—' : (value / 10000).toFixed(1) + ' 万'

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

const statusMeta: Record<string, { label: string; type: 'info' | 'primary' | 'success' | 'warning' | 'danger' }> = {
  PENDING: { label: '待生成', type: 'info' },
  GENERATING: { label: '生成中', type: 'primary' },
  DRAFT: { label: '草稿', type: 'warning' },
  CONFIRMED: { label: '已确认', type: 'success' },
  PUBLISHED: { label: '已发布', type: 'success' },
  GENERATION_FAILED: { label: '生成失败', type: 'danger' }
}

const currentStatusLabel = computed(() => statusMeta[current.value?.status ?? 'PENDING']?.label ?? '待生成')
const currentStatusType = computed(() => statusMeta[current.value?.status ?? 'PENDING']?.type ?? 'info')

const editable = computed(() => current.value?.editable ?? true)
</script>

<template>
  <div class="weekly-report-page">
    <div class="page-header">
      <h2>周报中心</h2>
      <div class="header-actions">
        <el-button :icon="Refresh" :loading="listLoading" @click="loadList">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建周报</el-button>
      </div>
    </div>

    <!-- 记录列表 -->
    <el-card shadow="never">
      <el-table :data="list" v-loading="listLoading" border>
        <el-table-column label="周" min-width="180">
          <template #default="{ row }">{{ row.weekStartDate }} ~ {{ row.periodEndDate }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusMeta[row.status]?.type ?? 'info'">
              {{ statusMeta[row.status]?.label ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="生成方式" width="110">
          <template #default="{ row }">
            <span v-if="row.generationMode">{{ row.generationMode }} · {{ row.generationModel ?? '—' }}</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="语雀" width="90">
          <template #default="{ row }">
            <el-link v-if="row.yuqueDocUrl" :href="row.yuqueDocUrl" target="_blank" type="primary">纪要链接</el-link>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="汇总表" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.sheetSyncStatus === 'MANUAL_DONE'" size="small" type="success">已回填</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="企微" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.wecomPushStatus === 'SUCCESS'" size="small" type="success">已推送</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="150">
          <template #default="{ row }">{{ row.updatedAt?.replace('T', ' ').slice(0, 16) ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openEditor(row.weekStartDate)">
              {{ row.editable ? '编辑' : '查看' }}
            </el-button>
            <el-button size="small" link type="primary" @click="openPublish(row)">同步</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无周报记录，点击右上角「新建周报」开始" :image-size="60" />
        </template>
      </el-table>
    </el-card>

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
        <div class="editor-header" v-if="current">
          <h3>
            周报（{{ current.weekStartDate }} ~ {{ current.periodEndDate }}）
            <el-tag :type="currentStatusType" effect="light" class="status-tag">{{ currentStatusLabel }}</el-tag>
          </h3>
          <div class="header-actions">
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
            <el-button :icon="CopyDocument" @click="copyReport">复制全文</el-button>
          </div>
        </div>

        <template v-if="current">
          <el-alert
            v-if="current.status === 'GENERATION_FAILED' && current.generationError"
            type="error"
            :title="current.generationError"
            :closable="false"
            class="error-alert"
          />

          <el-card shadow="never" class="section-card">
            <template #header>
              <div class="card-header">
                <span>人工输入</span>
                <el-button size="small" :loading="savingInputs" :disabled="!editable" @click="saveInputs">保存输入</el-button>
              </div>
            </template>
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
          </el-card>

          <el-card shadow="never" class="section-card" v-loading="factsLoading">
            <template #header><span>自动采集事实</span></template>
            <div v-if="facts" class="facts-panel">
              <div class="fact-block">
                <div class="fact-title">大事儿进度（{{ facts.keyMatters.length }}）</div>
                <el-table :data="facts.keyMatters" size="small" max-height="240" border>
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

          <el-card shadow="never" class="section-card">
            <template #header><span>周会纪要（Markdown，发布语雀用）</span></template>
            <el-input v-model="draft.minutesMarkdown" type="textarea" :rows="16" :disabled="!editable" />
          </el-card>
        </template>
      </div>
    </el-drawer>

    <!-- 发布与同步抽屉 -->
    <el-drawer v-model="publishVisible" title="发布与同步" size="440px">
      <template v-if="publishTarget">
        <div class="publish-week">{{ publishTarget.weekStartDate }} ~ {{ publishTarget.periodEndDate }}</div>
        <div class="publish-block">
          <div class="publish-title">语雀发布</div>
          <div class="publish-row">
            <span class="publish-label">TOC 状态</span>
            <el-tag v-if="publishTarget.yuqueTocStatus" size="small"
              :type="publishTarget.yuqueTocStatus === 'VERIFIED' ? 'success' : 'warning'">
              {{ publishTarget.yuqueTocStatus === 'VERIFIED' ? '已挂载验证' : publishTarget.yuqueTocStatus === 'MOVED' ? '已创建待人工挂目录' : '未找到节点' }}
            </el-tag>
            <span v-else class="publish-empty">未发布</span>
          </div>
          <div class="publish-row" v-if="publishTarget.yuqueDocUrl">
            <el-link :href="publishTarget.yuqueDocUrl" target="_blank" type="primary">{{ publishTarget.yuqueDocUrl }}</el-link>
          </div>
          <el-button
            type="primary"
            size="small"
            :loading="publishing"
            :disabled="!publishTarget.minutesMarkdown"
            @click="publishYuque"
          >发布语雀</el-button>
        </div>

        <el-divider />

        <div class="publish-block">
          <div class="publish-title">汇总表回填</div>
          <div class="publish-row">
            <span class="publish-label">目标行</span>
            <span>{{ publishTarget.sheetTargetInfo?.dateRangeLabel }} · {{ publishTarget.sheetTargetInfo?.teamName }}</span>
          </div>
          <div class="publish-row">
            <span class="publish-label">位置</span>
            <el-link :href="publishTarget.sheetTargetInfo?.sheetUrl" target="_blank" type="primary">
              {{ publishTarget.sheetTargetInfo?.sheetName }} 表 K 列
            </el-link>
          </div>
          <div class="publish-row">
            <span class="publish-label">状态</span>
            <el-tag v-if="publishTarget.sheetSyncStatus === 'MANUAL_DONE'" size="small" type="success">已回填</el-tag>
            <span v-else class="publish-empty">待回填</span>
          </div>
          <el-button size="small" :loading="markingSheet" :disabled="!publishTarget.yuqueDocUrl" @click="markSheet">
            标记已回填
          </el-button>
        </div>

        <el-divider />

        <div class="publish-block">
          <div class="publish-title">企微推送</div>
          <div class="publish-row">
            <span class="publish-label">状态</span>
            <el-tag v-if="publishTarget.wecomPushStatus === 'SUCCESS'" size="small" type="success">
              已推送 {{ publishTarget.wecomPushedAt?.slice(0, 16) ?? '' }}
            </el-tag>
            <span v-else class="publish-empty">未推送</span>
          </div>
          <el-button
            size="small"
            type="warning"
            :icon="Promotion"
            :loading="pushing"
            :disabled="publishTarget.status !== 'CONFIRMED' && publishTarget.status !== 'PUBLISHED'"
            @click="pushWecom"
          >推送企微</el-button>
        </div>
      </template>
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
}

.page-header h2 {
  margin: 0;
}

.header-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.create-body {
  display: flex;
  align-items: center;
  gap: 12px;
}

.create-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.create-hint {
  margin-top: 8px;
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}

.editor-wrap {
  padding: 0 4px;
}

.editor-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
  gap: 8px;
}

.editor-header h3 {
  margin: 0;
  display: flex;
  align-items: center;
  gap: 10px;
}

.status-tag {
  font-weight: 400;
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

.publish-week {
  font-weight: 600;
  margin-bottom: 12px;
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
