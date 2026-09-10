<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Download, Setting, Connection } from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import type {
  KpiAlertRule,
  KpiGroupRow,
  KpiNote,
  KpiReport,
  KpiSnapshotRow,
  KpiTarget,
  WorktimeStatus,
  WorktimeSyncLog
} from '@/types/kpi'

// ==================== 状态 ====================

const currentYear = new Date().getFullYear()
const year = ref(currentYear)
const loading = ref(false)
const report = ref<KpiReport | null>(null)
const activeTab = ref('report')

// ==================== 数据加载 ====================

const loadReport = async () => {
  loading.value = true
  try {
    report.value = await api.getKpiReport(year.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(loadReport)

// ==================== 月度矩阵组装 ====================

interface MonthCell {
  month: number
  snapshot: KpiSnapshotRow | null
}

const monthColumns = computed(() => {
  if (!report.value) return [] as number[]
  const months = new Set<number>()
  for (const group of report.value.groups) {
    for (const s of group.snapshots) months.add(Number(s.weekEndDate.slice(5, 7)))
  }
  return [...months].sort((a, b) => a - b)
})

/** 分组 × 月 → 月末快照（该月最后一个周日快照） */
const monthCell = (group: KpiGroupRow, month: number): MonthCell => {
  const inMonth = group.snapshots.filter(s => Number(s.weekEndDate.slice(5, 7)) === month)
  return { month, snapshot: inMonth.length ? inMonth[inMonth.length - 1] : null }
}

/** 月环比增量 = 本月末 YTD − 上月末 YTD（无上月快照时为 null） */
const monthDelta = (group: KpiGroupRow, month: number, field: 'ytdRevenue' | 'ytdProfit'): number | null => {
  const current = monthCell(group, month).snapshot
  if (!current) return null
  const prevMonth = month - 1
  if (prevMonth < 1) return current[field]
  const prev = monthCell(group, prevMonth).snapshot
  if (!prev) return current[field]
  return current[field] - prev[field]
}

/** 月度单元格备注（取该月最后一个带备注的快照） */
const monthNote = (group: KpiGroupRow, month: number, metric: 'revenue' | 'profit') => {
  const inMonth = [...group.snapshots.filter(s => Number(s.weekEndDate.slice(5, 7)) === month)].reverse()
  for (const s of inMonth) {
    const note = s.notes.find(n => n.metric === metric)
    if (note) return { note, snapshot: s }
  }
  return null
}

// ==================== 展示格式化 ====================

const fmtWan = (value: number | null | undefined) =>
  value === null || value === undefined ? '—' : (value / 10000).toFixed(1)

const fmtRate = (value: number | null | undefined) =>
  value === null || value === undefined ? '—' : (value * 100).toFixed(1) + '%'

const fmtDelta = (value: number | null | undefined) => {
  if (value === null || value === undefined) return '—'
  const wan = value / 10000
  return (wan > 0 ? '+' : '') + wan.toFixed(1)
}

const deltaClass = (value: number | null | undefined, alert?: string | null) => {
  if (alert === 'red' || (value !== null && value !== undefined && value <= 0)) return 'delta-red'
  if (alert === 'yellow') return 'delta-yellow'
  return 'delta-normal'
}

// ==================== 备注编辑 ====================

const noteDialog = reactive({
  visible: false,
  noteId: 0,
  title: '',
  deviationReason: '',
  isAbnormal: null as number | null,
  countermeasure: '',
  saving: false
})

const openNote = (groupName: string, monthLabel: string, metric: 'revenue' | 'profit', found: { note: KpiNote } | null) => {
  if (!found) {
    ElMessage.info('该单元格无异常记录')
    return
  }
  noteDialog.noteId = found.note.id
  noteDialog.title = `${groupName} · ${monthLabel} · ${metric === 'revenue' ? '营收' : '毛利'}环比增量`
  noteDialog.deviationReason = found.note.deviationReason ?? ''
  noteDialog.isAbnormal = found.note.isAbnormal
  noteDialog.countermeasure = found.note.countermeasure ?? ''
  noteDialog.visible = true
}

const saveNote = async () => {
  if (noteDialog.isAbnormal === 1 && !noteDialog.countermeasure.trim()) {
    ElMessage.warning('判定为异常时必须填写对策')
    return
  }
  noteDialog.saving = true
  try {
    await api.saveKpiNote(noteDialog.noteId, {
      deviationReason: noteDialog.deviationReason,
      isAbnormal: noteDialog.isAbnormal,
      countermeasure: noteDialog.countermeasure
    })
    ElMessage.success('备注已保存')
    noteDialog.visible = false
    await loadReport()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    noteDialog.saving = false
  }
}

// ==================== 快照操作 ====================

const runningSnapshot = ref(false)
const runSnapshot = async () => {
  runningSnapshot.value = true
  try {
    await api.runKpiSnapshot()
    ElMessage.success('本周快照已生成')
    await loadReport()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '快照生成失败')
  } finally {
    runningSnapshot.value = false
  }
}

const exportReport = async () => {
  try {
    await api.downloadKpiReport(year.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败')
  }
}

// ==================== 目标与规则 ====================

const targets = ref<KpiTarget[]>([])
const rules = ref<KpiAlertRule[]>([])
const targetRows = ref<Array<{ reportGroup: string; revenueTarget: number; profitTarget: number; remark: string }>>([])

const loadConfig = async () => {
  targets.value = await api.getKpiTargets(year.value)
  rules.value = await api.getKpiAlertRules()
  const groups = report.value?.groups.map(g => g.reportGroup) ?? []
  targetRows.value = groups.map(group => {
    const t = targets.value.find(item => item.reportGroup === group)
    return {
      reportGroup: group,
      revenueTarget: t ? t.revenueTarget / 10000 : 0,
      profitTarget: t ? t.profitTarget / 10000 : 0,
      remark: t?.remark ?? ''
    }
  })
}

const saveTargetRow = async (row: { reportGroup: string; revenueTarget: number; profitTarget: number; remark: string }) => {
  try {
    await api.saveKpiTarget({
      year: year.value,
      reportGroup: row.reportGroup,
      revenueTarget: Math.round(row.revenueTarget * 10000),
      profitTarget: Math.round(row.profitTarget * 10000),
      remark: row.remark
    })
    ElMessage.success(`「${row.reportGroup}」目标已保存`)
    await loadReport()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  }
}

const saveRule = async (rule: KpiAlertRule) => {
  try {
    await api.saveKpiAlertRule({
      reportGroup: rule.reportGroup,
      weeklyDivisor: rule.weeklyDivisor,
      yellowRatio: rule.yellowRatio,
      enabled: rule.enabled
    })
    ElMessage.success('规则已保存')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  }
}

// ==================== 工时系统集成 ====================

const wtStatus = ref<WorktimeStatus | null>(null)
const wtLogs = ref<WorktimeSyncLog[]>([])
const wtForm = reactive({ enabled: false, baseUrl: 'https://worktime.lucidata.cn', employeeNo: '', password: '' })
const wtTesting = ref(false)
const wtSyncing = ref(false)

const loadWorktime = async () => {
  try {
    wtStatus.value = await api.getWorktimeStatus()
    wtLogs.value = await api.getWorktimeSyncLogs()
    wtForm.enabled = wtStatus.value.enabled
    if (wtStatus.value.baseUrl) wtForm.baseUrl = wtStatus.value.baseUrl
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载工时系统集成状态失败')
  }
}

const saveWorktimeConfig = async () => {
  try {
    await api.saveWorktimeConfig({
      enabled: wtForm.enabled,
      baseUrl: wtForm.baseUrl,
      employeeNo: wtForm.employeeNo || undefined,
      password: wtForm.password || undefined
    })
    ElMessage.success('配置已保存')
    wtForm.employeeNo = ''
    wtForm.password = ''
    await loadWorktime()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  }
}

const testWorktime = async () => {
  wtTesting.value = true
  try {
    const result = await api.testWorktimeConnection()
    if (result.success) {
      ElMessage.success(`连接成功，可见业务线 ${result.visibleBusinessLines?.length ?? 0} 条，数据截止 ${result.dataCutoffDate ?? '—'}`)
    } else {
      ElMessage.error(result.message ?? '连接失败')
    }
    await loadWorktime()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '连接测试失败')
  } finally {
    wtTesting.value = false
  }
}

const syncContracts = async () => {
  wtSyncing.value = true
  try {
    const log = await api.syncWorktimeContracts()
    ElMessage.success(`合同同步完成：${log.upsertCount} 行，待映射 ${log.pendingCount} 行`)
    await loadWorktime()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '同步失败')
  } finally {
    wtSyncing.value = false
  }
}

const syncMonthly = async () => {
  wtSyncing.value = true
  try {
    const logs = await api.syncWorktimeMonthly()
    ElMessage.success(logs.length ? `月度同步完成：${logs.length} 个任务` : '无待同步月份')
    await loadWorktime()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '同步失败')
  } finally {
    wtSyncing.value = false
  }
}

const onTabChange = (tab: string) => {
  if (tab === 'config') loadConfig()
  if (tab === 'worktime') loadWorktime()
}

const syncTypeLabel: Record<string, string> = { contract: '合同明细', worklog: '工时明细', cost: '成本分析' }
</script>

<template>
  <div class="kpi-page">
    <div class="page-header">
      <h2>KPI 经营周报</h2>
      <div class="header-actions">
        <el-select v-model="year" style="width: 110px" @change="loadReport">
          <el-option v-for="y in [currentYear - 1, currentYear, currentYear + 1]" :key="y" :label="y + '年'" :value="y" />
        </el-select>
        <el-button :icon="Refresh" :loading="loading" @click="loadReport">刷新</el-button>
        <el-button type="primary" :loading="runningSnapshot" @click="runSnapshot">生成本周快照</el-button>
        <el-button :icon="Download" @click="exportReport">导出周报</el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <!-- ==================== 周报表格 ==================== -->
      <el-tab-pane label="周报" name="report">
        <div v-loading="loading" class="table-wrap">
          <table v-if="report" class="kpi-table">
            <thead>
              <tr>
                <th rowspan="2" class="col-group">业务线</th>
                <th rowspan="2">指标</th>
                <th colspan="2" class="col-target">KPI目标</th>
                <th v-for="m in monthColumns" :key="m" colspan="2">{{ m }}月YTD</th>
              </tr>
              <tr>
                <th class="col-target">营收</th>
                <th class="col-target">毛利</th>
                <template v-for="m in monthColumns" :key="m">
                  <th>营收</th>
                  <th>毛利</th>
                </template>
              </tr>
            </thead>
            <tbody>
              <template v-for="group in report.groups" :key="group.reportGroup">
                <!-- 实际行 -->
                <tr>
                  <td rowspan="3" class="col-group">{{ group.reportGroup }}</td>
                  <td>实际</td>
                  <td class="num">{{ fmtWan(group.revenueTarget) }}</td>
                  <td class="num">{{ fmtWan(group.profitTarget) }}</td>
                  <template v-for="m in monthColumns" :key="m">
                    <td class="num">{{ fmtWan(monthCell(group, m).snapshot?.ytdRevenue) }}</td>
                    <td class="num">{{ fmtWan(monthCell(group, m).snapshot?.ytdProfit) }}</td>
                  </template>
                </tr>
                <!-- 达成率行 -->
                <tr class="row-rate">
                  <td>达成率</td>
                  <td class="num">100%</td>
                  <td class="num">100%</td>
                  <template v-for="m in monthColumns" :key="m">
                    <td class="num">{{ fmtRate(monthCell(group, m).snapshot?.revenueRate) }}</td>
                    <td class="num">{{ fmtRate(monthCell(group, m).snapshot?.profitRate) }}</td>
                  </template>
                </tr>
                <!-- 环比增量行（异常可点击填备注） -->
                <tr class="row-delta">
                  <td>环比增量</td>
                  <td class="num">—</td>
                  <td class="num">—</td>
                  <template v-for="m in monthColumns" :key="m">
                    <td
                      class="num delta-cell"
                      :class="deltaClass(monthDelta(group, m, 'ytdRevenue'), monthNote(group, m, 'revenue')?.note.alertLevel)"
                      @click="openNote(group.reportGroup, m + '月', 'revenue', monthNote(group, m, 'revenue'))"
                    >{{ fmtDelta(monthDelta(group, m, 'ytdRevenue')) }}</td>
                    <td
                      class="num delta-cell"
                      :class="deltaClass(monthDelta(group, m, 'ytdProfit'), monthNote(group, m, 'profit')?.note.alertLevel)"
                      @click="openNote(group.reportGroup, m + '月', 'profit', monthNote(group, m, 'profit'))"
                    >{{ fmtDelta(monthDelta(group, m, 'ytdProfit')) }}</td>
                  </template>
                </tr>
              </template>
              <!-- 合计 -->
              <template v-if="report.total">
                <tr class="row-total">
                  <td rowspan="2" class="col-group">合计</td>
                  <td>实际 / 目标 {{ fmtWan(report.total.revenueTarget) }} / {{ fmtWan(report.total.profitTarget) }}</td>
                  <td class="num">{{ fmtWan(report.total.ytdRevenue) }}</td>
                  <td class="num">{{ fmtWan(report.total.ytdProfit) }}</td>
                  <td :colspan="monthColumns.length * 2" class="num">
                    达成率：营收 {{ fmtRate(report.total.revenueRate) }}，毛利 {{ fmtRate(report.total.profitRate) }}
                  </td>
                </tr>
                <tr class="row-total row-delta">
                  <td>最新周环比增量</td>
                  <td class="num" :class="deltaClass(report.total.weekDeltaRevenue)">{{ fmtDelta(report.total.weekDeltaRevenue) }}</td>
                  <td class="num" :class="deltaClass(report.total.weekDeltaProfit)">{{ fmtDelta(report.total.weekDeltaProfit) }}</td>
                  <td :colspan="monthColumns.length * 2"></td>
                </tr>
              </template>
            </tbody>
          </table>
          <el-empty v-else-if="!loading" description="暂无数据，请先生成本周快照" />
        </div>
        <p class="hint">
          金额单位：万元。当月未月结时人工成本为估算值；环比增量 ≤0 标红、明显偏小标黄，点击带色单元格填写偏差备注。
        </p>
      </el-tab-pane>

      <!-- ==================== 目标与规则 ==================== -->
      <el-tab-pane label="目标与规则" name="config">
        <el-card shadow="never">
          <template #header><span>年度 KPI 目标（{{ year }} 年，单位：万元）</span></template>
          <el-table :data="targetRows" border>
            <el-table-column prop="reportGroup" label="业务线" width="160" />
            <el-table-column label="营收目标">
              <template #default="{ row }"><el-input-number v-model="row.revenueTarget" :min="0" :precision="1" /></template>
            </el-table-column>
            <el-table-column label="毛利目标">
              <template #default="{ row }"><el-input-number v-model="row.profitTarget" :min="0" :precision="1" /></template>
            </el-table-column>
            <el-table-column label="备注">
              <template #default="{ row }"><el-input v-model="row.remark" placeholder="选填" /></template>
            </el-table-column>
            <el-table-column width="100">
              <template #default="{ row }">
                <el-button size="small" type="primary" @click="saveTargetRow(row)">保存</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" style="margin-top: 16px">
          <template #header><span><el-icon><Setting /></el-icon> 环比预警规则</span></template>
          <el-table :data="rules" border>
            <el-table-column label="适用范围" width="160">
              <template #default="{ row }">{{ row.reportGroup ?? '全局默认' }}</template>
            </el-table-column>
            <el-table-column label="周基准除数（月均目标÷该值）" width="220">
              <template #default="{ row }"><el-input-number v-model="row.weeklyDivisor" :min="1" :max="10" :precision="2" /></template>
            </el-table-column>
            <el-table-column label="偏小阈值（周基准×该比例）" width="220">
              <template #default="{ row }"><el-input-number v-model="row.yellowRatio" :min="0" :max="1" :precision="2" :step="0.1" /></template>
            </el-table-column>
            <el-table-column label="启用" width="80">
              <template #default="{ row }"><el-switch v-model="row.enabled" :active-value="1" :inactive-value="0" /></template>
            </el-table-column>
            <el-table-column width="100">
              <template #default="{ row }">
                <el-button size="small" type="primary" @click="saveRule(row)">保存</el-button>
              </template>
            </el-table-column>
          </el-table>
          <p class="hint">规则：环比增量 ≤ 0 → 标红；0 &lt; 环比增量 &lt; 月均目标 ÷ 周基准除数 × 偏小阈值 → 标黄。月均目标 = 年度目标 ÷ 12。</p>
        </el-card>
      </el-tab-pane>

      <!-- ==================== 工时系统集成 ==================== -->
      <el-tab-pane label="数据同步" name="worktime">
        <el-card shadow="never">
          <template #header><span><el-icon><Connection /></el-icon> 工时系统集成</span></template>
          <el-form label-width="120px" style="max-width: 560px">
            <el-form-item label="启用自动同步">
              <el-switch v-model="wtForm.enabled" />
            </el-form-item>
            <el-form-item label="系统地址">
              <el-input v-model="wtForm.baseUrl" placeholder="https://worktime.lucidata.cn" />
            </el-form-item>
            <el-form-item label="登录工号">
              <el-input v-model="wtForm.employeeNo" :placeholder="wtStatus?.credentialConfigured ? '已配置（留空保持不变）' : '如 00504'" />
            </el-form-item>
            <el-form-item label="登录密码">
              <el-input v-model="wtForm.password" type="password" show-password placeholder="留空保持不变" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="saveWorktimeConfig">保存配置</el-button>
              <el-button :loading="wtTesting" @click="testWorktime">测试连接</el-button>
              <el-button :loading="wtSyncing" @click="syncContracts">同步合同明细</el-button>
              <el-button :loading="wtSyncing" @click="syncMonthly">同步工时/成本</el-button>
            </el-form-item>
          </el-form>
          <p v-if="wtStatus" class="hint">
            最近测试：{{ wtStatus.lastTestStatus ?? '—' }} {{ wtStatus.lastTestedAt ?? '' }}
            <template v-if="wtStatus.lastTestMessage">｜{{ wtStatus.lastTestMessage }}</template>
          </p>
        </el-card>

        <el-card shadow="never" style="margin-top: 16px">
          <template #header><span>同步日志（最近 50 条）</span></template>
          <el-table :data="wtLogs" border size="small">
            <el-table-column label="类型" width="100">
              <template #default="{ row }">{{ syncTypeLabel[row.syncType] ?? row.syncType }}</template>
            </el-table-column>
            <el-table-column prop="scope" label="范围" width="90" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'success' ? 'success' : row.status === 'failed' ? 'danger' : 'info'" size="small">
                  {{ row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="totalCount" label="行数" width="80" />
            <el-table-column prop="pendingCount" label="待映射" width="80" />
            <el-table-column prop="triggeredBy" label="触发" width="90" />
            <el-table-column prop="startedAt" label="开始时间" width="170" />
            <el-table-column prop="message" label="信息" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- ==================== 备注弹窗 ==================== -->
    <el-dialog v-model="noteDialog.visible" :title="noteDialog.title" width="520px">
      <el-form label-width="90px">
        <el-form-item label="偏差原因" required>
          <el-input v-model="noteDialog.deviationReason" type="textarea" :rows="3" placeholder="说明环比增量异常的原因" />
        </el-form-item>
        <el-form-item label="是否异常" required>
          <el-radio-group v-model="noteDialog.isAbnormal">
            <el-radio :value="1">是</el-radio>
            <el-radio :value="0">否</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="noteDialog.isAbnormal === 1" label="对策" required>
          <el-input v-model="noteDialog.countermeasure" type="textarea" :rows="3" placeholder="判定为异常，请填写具体对策" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="noteDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="noteDialog.saving" @click="saveNote">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.kpi-page { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.page-header h2 { margin: 0; }
.header-actions { display: flex; gap: 8px; }
.table-wrap { overflow-x: auto; }
.kpi-table { border-collapse: collapse; width: 100%; font-size: 13px; }
.kpi-table th, .kpi-table td { border: 1px solid var(--el-border-color-lighter); padding: 6px 10px; text-align: center; white-space: nowrap; }
.kpi-table thead th { background: var(--el-fill-color-light); }
.kpi-table .col-group { font-weight: 600; background: var(--el-fill-color-lighter); }
.kpi-table .col-target { background: var(--el-color-primary-light-9); }
.kpi-table td.num { text-align: right; font-variant-numeric: tabular-nums; }
.row-rate td { color: var(--el-text-color-secondary); }
.delta-cell { cursor: pointer; }
.delta-red { color: var(--el-color-danger); font-weight: 600; background: var(--el-color-danger-light-9); }
.delta-yellow { color: var(--el-color-warning-dark-2); font-weight: 600; background: var(--el-color-warning-light-9); }
.delta-normal { color: var(--el-text-color-regular); }
.row-total td { font-weight: 600; background: var(--el-fill-color-lighter); }
.hint { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 8px; }
</style>
