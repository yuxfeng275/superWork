<script setup lang="ts">
import { ref } from 'vue'

const stats = ref([
  { label: '总需求数', value: 48, change: '+12%' },
  { label: '进行中', value: 15, change: '+5%' },
  { label: '本月上线', value: 8, change: '+2' },
  { label: '平均交付周期', value: '18天', change: '-3天' }
])

const monthlyData = ref([
  { month: '1月', completed: 12, inProgress: 8 },
  { month: '2月', completed: 15, inProgress: 10 },
  { month: '3月', completed: 18, inProgress: 12 },
  { month: '4月', completed: 10, inProgress: 15 }
])
</script>

<template>
  <div class="statistics-page">
    <div class="page-header">
      <h2 class="page-title">数据统计</h2>
    </div>

    <div class="stats-grid">
      <div v-for="stat in stats" :key="stat.label" class="stat-card">
        <div class="stat-value">{{ stat.value }}</div>
        <div class="stat-label">{{ stat.label }}</div>
        <div class="stat-change" :class="stat.change.startsWith('-') ? 'change-down' : 'change-up'">
          {{ stat.change }}
        </div>
      </div>
    </div>

    <div class="chart-card">
      <h3 class="card-title">月度需求趋势</h3>
      <div class="chart-placeholder">
        <div class="bar-chart">
          <div v-for="item in monthlyData" :key="item.month" class="bar-group">
            <div class="bar-completed" :style="{ height: (item.completed / 20 * 100) + '%' }"></div>
            <div class="bar-progress" :style="{ height: (item.inProgress / 20 * 100) + '%' }"></div>
            <span class="bar-label">{{ item.month }}</span>
          </div>
        </div>
      </div>
      <div class="chart-legend">
        <span class="legend-item"><span class="legend-dot completed"></span>已完成</span>
        <span class="legend-item"><span class="legend-dot progress"></span>进行中</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-header {
  margin-bottom: 24px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--gray-800);
  margin: 0;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}

.stat-card {
  background: white;
  border-radius: var(--radius-lg);
  padding: 20px;
  box-shadow: var(--shadow-sm);
}

.stat-value {
  font-size: 32px;
  font-weight: 700;
  color: var(--gray-800);
  margin-bottom: 4px;
}

.stat-label {
  font-size: 14px;
  color: var(--gray-500);
  margin-bottom: 8px;
}

.stat-change {
  font-size: 13px;
  font-weight: 500;
}

.change-up {
  color: #10b981;
}

.change-down {
  color: #ef4444;
}

.chart-card {
  background: white;
  border-radius: var(--radius-lg);
  padding: 20px;
  box-shadow: var(--shadow-sm);
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--gray-800);
  margin: 0 0 20px;
}

.chart-placeholder {
  height: 200px;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.bar-chart {
  display: flex;
  gap: 24px;
  align-items: flex-end;
  height: 100%;
}

.bar-group {
  display: flex;
  gap: 4px;
  align-items: flex-end;
}

.bar-completed,
.bar-progress {
  width: 24px;
  border-radius: 4px 4px 0 0;
  transition: height 0.3s ease;
}

.bar-completed {
  background: var(--primary);
}

.bar-progress {
  background: var(--primary-light);
}

.bar-label {
  font-size: 12px;
  color: var(--gray-500);
  text-align: center;
  margin-top: 8px;
}

.chart-legend {
  display: flex;
  justify-content: center;
  gap: 24px;
  margin-top: 16px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--gray-600);
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 2px;
}

.legend-dot.completed {
  background: var(--primary);
}

.legend-dot.progress {
  background: var(--primary-light);
}
</style>
