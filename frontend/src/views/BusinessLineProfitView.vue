<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Download } from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import type { BizLineProfitReport, BizLineProfitRow, WorktimeSyncLog } from '@/types/businessLineProfit'

const currentYear = new Date().getFullYear()
const year = ref(currentYear)
const loading = ref(false)
const syncing = ref(false)
const report = ref<BizLineProfitReport | null>(null)
const syncLogs = ref<WorktimeSyncLog[]>([])

// 业务线筛选：pill 多选；空数组=全部
const filterLineNames = ref<string[]>([])

const toggleLineFilter = (name: string) => {
  filterLineNames.value = filterLineNames.value.includes(name)
    ? filterLineNames.value.filter(item => item !== name)
    : [...filterLineNames.value, name]
}

const filteredLines = computed(() => {
  if (!report.value) return []
  if (!filterLineNames.value.length) return report.value.lines
  return report.value.lines.filter(line => filterLineNames.value.includes(line.businessLineName))
})

const latestSyncLog = computed(() => syncLogs.value[0] ?? null)

const formatWan = (value?: number | null) => {
  if (value == null) return '—'
  const num = Number(value) / 10000
  if (num === 0) return '—'
  return (Math.round(num * 100) / 100).toLocaleString('zh-CN')
}

const formatRate = (value?: number | null) => {
  if (value == null) return '—'
  return `${Number(value).toFixed(1)}%`
}

const formatHours = (value?: number | null) => {
  if (value == null) return '—'
  const num = Number(value)
  if (num === 0) return '—'
  return String(Math.round(num * 100) / 100)
}

const isNegative = (value?: number | null) => value != null && Number(value) < 0

const loadReport = async () => {
  loading.value = true
  try {
    report.value = await api.getBlProfitReport(year.value)
    const valid = new Set(report.value.lines.map(line => line.businessLineName))
    filterLineNames.value = filterLineNames.value.filter(name => valid.has(name))
  } catch (error) {
    ElMessage.error(error instanceof Error && error.message ? error.message : '业务线利润报表加载失败')
  } finally {
    loading.value = false
  }
}

const loadSyncLogs = async () => {
  try {
    syncLogs.value = await api.getBlProfitSyncLogs(10)
  } catch {
    syncLogs.value = []
  }
}

// 手动同步：整年逐月从工时系统拉取（整月覆盖），完成后刷新报表
const syncFromWorktime = async () => {
  syncing.value = true
  try {
    const logs = await api.syncBlProfit({ year: year.value })
    const success = logs.filter(log => log.status === 'success').length
    const failed = logs.filter(log => log.status === 'failed')
    if (failed.length > 0) {
      ElMessage.warning(`同步完成：${success} 个月成功，${failed.length} 个月失败（${failed[0].scope}: ${failed[0].message ?? ''}）`)
    } else {
      ElMessage.success(`同步完成：${success} 个月份已更新`)
    }
    await Promise.all([loadReport(), loadSyncLogs()])
  } catch (error) {
    ElMessage.error(error instanceof Error && error.message ? error.message : '同步失败')
  } finally {
    syncing.value = false
  }
}

const rowKey = (lineName: string, row: BizLineProfitRow) => `${lineName}-${row.yearMonth ?? 'total'}`

onMounted(() => {
  void loadReport()
  void loadSyncLogs()
})
</script>

<template>
  <div class="bl-profit-page" v-loading="loading">
    <header class="page-head">
      <div>
        <span class="eyebrow">FINANCE</span>
        <h2>业务线利润</h2>
        <p>业务线 × 月 真实营收与利润，数据源自工时系统业务线利润报表；每业务线各月合计即 YTD。</p>
      </div>
      <div class="head-actions">
        <el-select v-model="year" aria-label="选择年份" style="width: 130px" @change="loadReport">
          <el-option v-for="y in [currentYear - 1, currentYear, currentYear + 1]" :key="y" :label="`${y}年`" :value="y" />
        </el-select>
        <el-button :icon="Refresh" aria-label="刷新" @click="loadReport" />
        <el-button type="primary" :icon="Download" :loading="syncing" @click="syncFromWorktime">同步工时系统</el-button>
      </div>
    </header>

    <p v-if="latestSyncLog" class="sync-status" :class="{ failed: latestSyncLog.status === 'failed' }">
      最近同步：{{ latestSyncLog.scope }} ·
      {{ latestSyncLog.status === 'success' ? `成功 ${latestSyncLog.upsertCount} 行` : latestSyncLog.status === 'failed' ? `失败（${latestSyncLog.message ?? ''}）` : '进行中' }}
      · {{ latestSyncLog.finishedAt || latestSyncLog.startedAt }}
    </p>

    <template v-if="report && report.lines.length > 0">
      <div class="filter-pills" aria-label="业务线筛选">
        <button class="filter-pill" :class="{ active: !filterLineNames.length }" @click="filterLineNames = []">全部业务线</button>
        <button
          v-for="line in report.lines"
          :key="line.businessLineName"
          class="filter-pill"
          :class="{ active: filterLineNames.includes(line.businessLineName) }"
          @click="toggleLineFilter(line.businessLineName)"
        >{{ line.businessLineName }}</button>
      </div>

      <div class="table-wrapper">
        <table class="profit-table">
          <thead>
            <tr>
              <th class="col-line">业务线</th>
              <th class="col-month">月</th>
              <th class="col-num">营业收入(万)</th>
              <th class="col-num">考核毛利(万)</th>
              <th class="col-num">考核毛利率</th>
              <th class="col-num">净利润(万)</th>
              <th class="col-num">净利率</th>
              <th class="col-num">工时(人月)</th>
            </tr>
          </thead>
          <tbody v-for="line in filteredLines" :key="line.businessLineName">
            <tr v-for="month in line.months" :key="rowKey(line.businessLineName, month)" class="month-row">
              <td class="col-line">{{ line.businessLineName }}</td>
              <td class="col-month">{{ month.yearMonth }}</td>
              <td class="col-num" :class="{ negative: isNegative(month.revenue) }">{{ formatWan(month.revenue) }}</td>
              <td class="col-num" :class="{ negative: isNegative(month.grossProfit) }">{{ formatWan(month.grossProfit) }}</td>
              <td class="col-num" :class="{ negative: isNegative(month.grossProfitRate) }">{{ formatRate(month.grossProfitRate) }}</td>
              <td class="col-num" :class="{ negative: isNegative(month.netProfit) }">{{ formatWan(month.netProfit) }}</td>
              <td class="col-num" :class="{ negative: isNegative(month.netProfitRate) }">{{ formatRate(month.netProfitRate) }}</td>
              <td class="col-num">{{ formatHours(month.totalHours) }}</td>
            </tr>
            <tr class="ytd-row">
              <td class="col-line">{{ line.businessLineName }}</td>
              <td class="col-month">YTD</td>
              <td class="col-num" :class="{ negative: isNegative(line.ytd.revenue) }">{{ formatWan(line.ytd.revenue) }}</td>
              <td class="col-num" :class="{ negative: isNegative(line.ytd.grossProfit) }">{{ formatWan(line.ytd.grossProfit) }}</td>
              <td class="col-num" :class="{ negative: isNegative(line.ytd.grossProfitRate) }">{{ formatRate(line.ytd.grossProfitRate) }}</td>
              <td class="col-num" :class="{ negative: isNegative(line.ytd.netProfit) }">{{ formatWan(line.ytd.netProfit) }}</td>
              <td class="col-num" :class="{ negative: isNegative(line.ytd.netProfitRate) }">{{ formatRate(line.ytd.netProfitRate) }}</td>
              <td class="col-num">{{ formatHours(line.ytd.totalHours) }}</td>
            </tr>
          </tbody>
          <tfoot>
            <tr class="grand-total-row">
              <td class="col-line">合计</td>
              <td class="col-month">YTD</td>
              <td class="col-num" :class="{ negative: isNegative(report.totalYtd.revenue) }">{{ formatWan(report.totalYtd.revenue) }}</td>
              <td class="col-num" :class="{ negative: isNegative(report.totalYtd.grossProfit) }">{{ formatWan(report.totalYtd.grossProfit) }}</td>
              <td class="col-num" :class="{ negative: isNegative(report.totalYtd.grossProfitRate) }">{{ formatRate(report.totalYtd.grossProfitRate) }}</td>
              <td class="col-num" :class="{ negative: isNegative(report.totalYtd.netProfit) }">{{ formatWan(report.totalYtd.netProfit) }}</td>
              <td class="col-num" :class="{ negative: isNegative(report.totalYtd.netProfitRate) }">{{ formatRate(report.totalYtd.netProfitRate) }}</td>
              <td class="col-num">{{ formatHours(report.totalYtd.totalHours) }}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </template>
    <el-empty v-else-if="!loading" description="暂无数据，点击右上角「同步工时系统」拉取业务线利润报表" />
  </div>
</template>

<style scoped>
.bl-profit-page {
  padding: 24px 28px 48px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.eyebrow {
  font-size: 11px;
  letter-spacing: 0.16em;
  color: var(--text-tertiary, #98a2b3);
}

.page-head h2 {
  margin: 4px 0 6px;
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary, #1d2939);
}

.page-head p {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary, #667085);
}

.head-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-shrink: 0;
}

.sync-status {
  margin: 0;
  font-size: 12px;
  color: var(--text-tertiary, #98a2b3);
}

.sync-status.failed {
  color: var(--danger, #d92d20);
}

.filter-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.filter-pill {
  border: 1px solid var(--border-color, #e4e7ec);
  background: var(--bg-primary, #fff);
  border-radius: 999px;
  padding: 4px 14px;
  font-size: 13px;
  color: var(--text-secondary, #667085);
  cursor: pointer;
}

.filter-pill.active {
  border-color: var(--primary, #2e5ef0);
  color: var(--primary, #2e5ef0);
  background: var(--primary-plain, #eef4ff);
}

.table-wrapper {
  overflow-x: auto;
}

.profit-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  background: var(--bg-primary, #fff);
}

.profit-table th,
.profit-table td {
  border: 1px solid var(--border-color, #e4e7ec);
  padding: 8px 12px;
  white-space: nowrap;
}

.profit-table thead th {
  background: var(--bg-secondary, #f9fafb);
  color: var(--text-secondary, #667085);
  font-weight: 600;
  text-align: left;
}

.col-line {
  min-width: 200px;
}

.col-month {
  width: 90px;
}

.col-num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.negative {
  color: var(--danger, #d92d20);
}

.ytd-row td {
  background: var(--bg-secondary, #f9fafb);
  font-weight: 600;
}

.grand-total-row td {
  background: var(--primary-plain, #eef4ff);
  font-weight: 700;
}
</style>
