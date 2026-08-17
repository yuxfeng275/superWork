<script setup lang="ts">
import { computed } from 'vue'
import { Calendar, CircleCheckFilled, FolderOpened, User, WarningFilled } from '@element-plus/icons-vue'
import type { WorkItemAnalysis, WorkItemDistributionItem } from '@/types/work-item'

interface DistributionSection {
  key: 'status' | 'project' | 'owner' | 'source' | 'priority'
  title: string
  rows: WorkItemDistributionItem[]
  limit?: number
  interactive?: boolean
}

const props = withDefaults(defineProps<{
  title: string
  subtitle?: string
  analysis: WorkItemAnalysis
  sections: DistributionSection[]
}>(), {
  subtitle: '基于当前筛选范围的实时结构分布'
})

const emit = defineEmits<{
  select: [section: DistributionSection['key'], item: WorkItemDistributionItem]
}>()

const ageTone: Record<string, string> = {
  D1_7: 'fresh',
  D8_30: 'attention',
  D31_90: 'serious',
  D90_PLUS: 'critical'
}

const overdueAgeRows = computed(() => props.analysis.overdueAgeDistribution || [])
const riskProjects = computed(() => (props.analysis.overdueProjectDistribution || []).slice(0, 3))
const riskOwners = computed(() => (props.analysis.overdueOwnerDistribution || []).slice(0, 3))
const hasOverdue = computed(() => Number(props.analysis.overdueIncompleteCount || 0) > 0)

const rowWidth = (percentage: number) => `${Math.max(percentage > 0 ? 4 : 0, Math.min(100, percentage))}%`
const rankWidth = (item: WorkItemDistributionItem, rows: WorkItemDistributionItem[]) => {
  const maximum = Math.max(...rows.map(row => Number(row.count || 0)), 1)
  return `${Math.max(8, Number(item.count || 0) / maximum * 100)}%`
}
</script>

<template>
  <section class="analysis-section" aria-label="工作项统计分析">
    <header class="analysis-header">
      <div>
        <h2>{{ title }}</h2>
        <p>{{ subtitle }}</p>
      </div>
      <div class="analysis-rate">
        <span>完成率</span>
        <strong>{{ Number(analysis.completionRate || 0).toFixed(1) }}%</strong>
      </div>
    </header>

    <section class="risk-dashboard" aria-label="超期风险看板">
      <div class="risk-overview">
        <div class="risk-title">
          <el-icon aria-hidden="true"><WarningFilled /></el-icon>
          <span>交付风险</span>
        </div>
        <div class="risk-metrics">
          <div class="risk-primary">
            <strong>{{ analysis.overdueIncompleteCount || 0 }}</strong>
            <span>超期未完成</span>
          </div>
          <div class="risk-secondary">
            <strong>{{ analysis.missingDueDateCount || 0 }}</strong>
            <span>未设置计划</span>
          </div>
        </div>
        <p>计划完成时间早于今天，且当前状态未完成</p>
      </div>

      <div v-if="hasOverdue" class="risk-age">
        <header>
          <div>
            <span class="risk-kicker">逾期时长</span>
            <strong>风险老化程度</strong>
          </div>
          <el-icon aria-hidden="true"><Calendar /></el-icon>
        </header>
        <div class="age-stack" role="img" aria-label="超期时长占比分布">
          <span
            v-for="item in overdueAgeRows"
            :key="item.key"
            class="age-segment"
            :class="`age-${ageTone[item.key] || 'critical'}`"
            :style="{ width: `${Math.max(3, Number(item.percentage || 0))}%` }"
            :title="`${item.label}：${item.count} 项，占 ${Number(item.percentage || 0).toFixed(1)}%`"
          />
        </div>
        <div class="age-legend">
          <div v-for="item in overdueAgeRows" :key="`legend-${item.key}`">
            <i :class="`age-${ageTone[item.key] || 'critical'}`" aria-hidden="true" />
            <span>{{ item.label }}</span>
            <strong>{{ item.count }}</strong>
          </div>
        </div>
      </div>

      <div v-if="hasOverdue" class="risk-ranking">
        <div class="ranking-column">
          <header>
            <span><el-icon aria-hidden="true"><FolderOpened /></el-icon>风险项目</span>
            <small>TOP {{ riskProjects.length }}</small>
          </header>
          <div v-if="riskProjects.length" class="ranking-list">
            <div v-for="item in riskProjects" :key="`risk-project-${item.key}`" class="ranking-row">
              <div><span :title="item.label">{{ item.label }}</span><strong>{{ item.count }}</strong></div>
              <i><b :style="{ width: rankWidth(item, riskProjects) }" /></i>
            </div>
          </div>
          <span v-else class="ranking-empty">暂无项目归属</span>
        </div>

        <div class="ranking-column">
          <header>
            <span><el-icon aria-hidden="true"><User /></el-icon>风险负责人</span>
            <small>TOP {{ riskOwners.length }}</small>
          </header>
          <div v-if="riskOwners.length" class="ranking-list">
            <div v-for="item in riskOwners" :key="`risk-owner-${item.key}`" class="ranking-row">
              <div><span :title="item.label">{{ item.label }}</span><strong>{{ item.count }}</strong></div>
              <i><b :style="{ width: rankWidth(item, riskOwners) }" /></i>
            </div>
          </div>
          <span v-else class="ranking-empty">暂无负责人</span>
        </div>
      </div>

      <div v-else class="risk-clear">
        <el-icon aria-hidden="true"><CircleCheckFilled /></el-icon>
        <div><strong>当前没有超期工作项</strong><span>计划内事项保持正常推进</span></div>
      </div>
    </section>

    <div class="analysis-grid">
      <article v-for="section in sections" :key="section.key" class="distribution-panel">
        <header>
          <h3>{{ section.title }}</h3>
          <span>{{ section.rows.reduce((sum, item) => sum + Number(item.count || 0), 0) }} 项</span>
        </header>
        <div v-if="section.rows.length" class="distribution-list">
          <button
            v-for="item in section.rows.slice(0, section.limit || 6)"
            :key="`${section.key}-${item.key}`"
            type="button"
            class="distribution-row"
            :class="{ interactive: section.interactive !== false }"
            :disabled="section.interactive === false"
            :aria-label="`${item.label} ${item.count}`"
            @click="emit('select', section.key, item)"
          >
            <div class="distribution-meta">
              <span>{{ item.label }}</span>
              <strong>{{ item.count }}</strong>
            </div>
            <div class="distribution-track" aria-hidden="true">
              <i :class="`tone-${section.key}`" :style="{ width: rowWidth(item.percentage) }" />
            </div>
            <small>{{ Number(item.percentage || 0).toFixed(1) }}%</small>
          </button>
        </div>
        <el-empty v-else description="暂无分布数据" :image-size="42" />
      </article>
    </div>
    <slot />
  </section>
</template>

<style scoped>
.analysis-section { margin-bottom: 20px; border: 1px solid #dde2e8; border-radius: 6px; overflow: hidden; background: #fff; }
.analysis-header { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 16px 18px 14px; }
.analysis-header h2 { margin: 0; color: #252a31; font-size: 17px; line-height: 1.3; letter-spacing: 0; }
.analysis-header p { margin: 4px 0 0; color: #7a828d; font-size: 12px; }
.analysis-rate { min-width: 102px; padding-left: 18px; border-left: 1px solid #e2e5e9; text-align: right; }
.analysis-rate span { display: block; color: #737b86; font-size: 11px; }
.analysis-rate strong { display: block; margin-top: 2px; color: #277451; font-size: 22px; }
.risk-dashboard { display: grid; grid-template-columns: 220px minmax(280px, .9fr) minmax(360px, 1.1fr); min-height: 172px; border-top: 1px solid #eadfd8; border-bottom: 1px solid #eadfd8; background: #fffaf6; }
.risk-overview { padding: 17px 18px; border-right: 1px solid #eadfd8; }
.risk-title { display: flex; align-items: center; gap: 7px; color: #833f35; font-size: 12px; font-weight: 700; }
.risk-title .el-icon { color: #c04f3f; font-size: 16px; }
.risk-metrics { display: flex; align-items: flex-end; gap: 20px; margin-top: 13px; }
.risk-primary strong, .risk-secondary strong { display: block; line-height: 1; letter-spacing: 0; }
.risk-primary strong { color: #b53d32; font-size: 38px; }
.risk-secondary strong { color: #5f6874; font-size: 24px; }
.risk-primary span, .risk-secondary span { display: block; margin-top: 5px; color: #716d6a; font-size: 11px; white-space: nowrap; }
.risk-overview p { margin: 13px 0 0; color: #9a8177; font-size: 10px; line-height: 1.45; }
.risk-age { padding: 17px 20px; border-right: 1px solid #eadfd8; }
.risk-age > header, .ranking-column > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.risk-age > header > div { display: flex; flex-direction: column; gap: 2px; }
.risk-kicker { color: #9a7569; font-size: 10px; }
.risk-age header strong { color: #463f3c; font-size: 13px; }
.risk-age header .el-icon { color: #ae796d; font-size: 18px; }
.age-stack { display: flex; gap: 2px; width: 100%; height: 13px; margin-top: 18px; overflow: hidden; border-radius: 3px; background: #f0e8e3; }
.age-segment { display: block; min-width: 3px; height: 100%; }
.age-fresh { background: #e1a84a !important; }
.age-attention { background: #de7c3f !important; }
.age-serious { background: #c95440 !important; }
.age-critical { background: #943b45 !important; }
.age-legend { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px 14px; margin-top: 14px; }
.age-legend > div { display: grid; grid-template-columns: 7px minmax(0, 1fr) auto; align-items: center; gap: 7px; min-width: 0; }
.age-legend i { width: 7px; height: 7px; border-radius: 1px; }
.age-legend span { overflow: hidden; color: #776d69; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.age-legend strong { color: #493f3b; font-size: 11px; }
.risk-ranking { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.ranking-column { min-width: 0; padding: 17px 18px; }
.ranking-column + .ranking-column { border-left: 1px solid #eadfd8; }
.ranking-column header span { display: inline-flex; align-items: center; gap: 6px; color: #4e4743; font-size: 12px; font-weight: 700; }
.ranking-column header .el-icon { color: #a66759; font-size: 14px; }
.ranking-column header small { color: #aa8e84; font-size: 9px; }
.ranking-list { display: grid; gap: 10px; margin-top: 13px; }
.ranking-row { min-width: 0; }
.ranking-row > div { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.ranking-row span { overflow: hidden; color: #6d625e; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.ranking-row strong { min-width: 22px; color: #5c4943; font-size: 10px; text-align: right; }
.ranking-row > i { display: block; width: 100%; height: 4px; margin-top: 4px; overflow: hidden; border-radius: 1px; background: #eee4df; }
.ranking-row > i b { display: block; height: 100%; border-radius: 1px; background: #be6756; }
.ranking-empty { display: block; margin-top: 24px; color: #aa9690; font-size: 11px; }
.risk-clear { grid-column: 2 / -1; display: flex; align-items: center; justify-content: center; gap: 12px; min-height: 150px; color: #287451; }
.risk-clear .el-icon { font-size: 29px; }
.risk-clear strong, .risk-clear span { display: block; }
.risk-clear strong { font-size: 14px; }
.risk-clear span { margin-top: 3px; color: #718079; font-size: 11px; }
.analysis-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.distribution-panel { min-width: 0; padding: 15px 18px 17px; border-right: 1px solid #e3e6ea; border-bottom: 1px solid #e3e6ea; }
.distribution-panel:nth-child(even) { border-right: 0; }
.distribution-panel > header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.distribution-panel h3 { margin: 0; color: #3d434c; font-size: 13px; letter-spacing: 0; }
.distribution-panel header span { color: #858c96; font-size: 11px; }
.distribution-list { display: grid; gap: 7px; }
.distribution-row { display: grid; grid-template-columns: minmax(95px, 1fr) minmax(90px, 1.4fr) 48px; align-items: center; gap: 10px; width: 100%; padding: 3px 0; border: 0; background: transparent; color: inherit; text-align: left; }
.distribution-row.interactive { cursor: pointer; }
.distribution-row.interactive:hover .distribution-meta span { color: #285fae; }
.distribution-row:disabled { opacity: 1; }
.distribution-meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-width: 0; }
.distribution-meta span { overflow: hidden; color: #59616c; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.distribution-meta strong { color: #303640; font-size: 12px; }
.distribution-track { height: 7px; overflow: hidden; border-radius: 2px; background: #edf0f3; }
.distribution-track i { display: block; height: 100%; border-radius: 2px; background: #4777c4; }
.distribution-track i.tone-project { background: #2b8a6e; }
.distribution-track i.tone-owner { background: #b27a25; }
.distribution-track i.tone-source { background: #7a6aae; }
.distribution-track i.tone-priority { background: #c35c58; }
.distribution-row small { color: #828a95; font-size: 11px; text-align: right; }
@media (max-width: 1120px) {
  .risk-dashboard { grid-template-columns: 220px minmax(260px, 1fr); }
  .risk-age { border-right: 0; }
  .risk-ranking { grid-column: 1 / -1; border-top: 1px solid #eadfd8; }
}
@media (max-width: 760px) {
  .analysis-section { border-right: 0; border-left: 0; border-radius: 0; }
  .risk-dashboard { grid-template-columns: 1fr; }
  .risk-overview, .risk-age { border-right: 0; }
  .risk-overview { border-bottom: 1px solid #eadfd8; }
  .risk-ranking { grid-column: auto; grid-template-columns: 1fr; }
  .ranking-column + .ranking-column { border-top: 1px solid #eadfd8; border-left: 0; }
  .risk-clear { grid-column: auto; min-height: 110px; }
  .analysis-grid { grid-template-columns: 1fr; }
  .distribution-panel { border-right: 0; }
}
@media (max-width: 520px) {
  .analysis-header { align-items: flex-start; padding: 14px; }
  .analysis-rate { min-width: 84px; padding-left: 12px; }
  .risk-overview, .risk-age, .ranking-column { padding-right: 14px; padding-left: 14px; }
  .distribution-panel { padding-right: 14px; padding-left: 14px; }
  .distribution-row { grid-template-columns: minmax(90px, 1fr) minmax(70px, 1fr) 42px; gap: 7px; }
}
</style>
