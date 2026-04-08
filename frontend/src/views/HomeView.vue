<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api } from '@/utils/api'

const stats = ref([
  { label: '进行中需求', value: 0, icon: 'Document', color: '#667eea' },
  { label: '待上线', value: 0, icon: 'Upload', color: '#f59e0b' },
  { label: '本月交付', value: 0, icon: 'Finished', color: '#10b981' },
  { label: '超期风险', value: 0, icon: 'Warning', color: '#ef4444' }
])

const recentRequirements = ref<any[]>([])

const loadData = async () => {
  try {
    // 加载需求列表
    const requirementsData = await api.getRequirements({ size: 100 })

    // 计算统计数据
    const allRequirements = requirementsData.records || []

    // 进行中需求
    stats.value[0].value = allRequirements.filter((r: any) =>
      ['评估中', '设计中', '开发中', '测试中'].includes(r.status)
    ).length

    // 待上线
    stats.value[1].value = allRequirements.filter((r: any) => r.status === '待上线').length

    // 本月交付 (已上线/已交付/已验收)
    const now = new Date()
    const monthStart = new Date(now.getFullYear(), now.getMonth(), 1)
    stats.value[2].value = allRequirements.filter((r: any) => {
      if (!['已上线', '已交付', '已验收'].includes(r.status)) return false
      const updatedAt = r.updatedAt ? new Date(r.updatedAt) : null
      return updatedAt && updatedAt >= monthStart
    }).length

    // 超期风险 (高优先级且待评估/评估中)
    stats.value[3].value = allRequirements.filter((r: any) =>
      r.priority === '高' && ['待评估', '评估中'].includes(r.status)
    ).length

    // 最近需求
    recentRequirements.value = allRequirements.slice(0, 5).map((r: any) => ({
      id: String(r.id),
      title: r.title,
      status: r.status,
      statusClass: getStatusClass(r.status),
      project: r.projectName || ''
    }))
  } catch (error) {
    console.error('加载数据失败:', error)
  }
}

const getStatusClass = (status: string): string => {
  const statusMap: Record<string, string> = {
    '待评估': 'status-default',
    '评估中': 'status-info',
    '已拒绝': 'status-danger',
    '待设计': 'status-default',
    '设计中': 'status-primary',
    '待确认': 'status-default',
    '开发中': 'status-info',
    '测试中': 'status-warning',
    '待上线': 'status-warning',
    '已上线': 'status-success',
    '已交付': 'status-success',
    '已验收': 'status-success'
  }
  return statusMap[status] || 'status-default'
}

onMounted(() => {
  loadData()
})
</script>

<template>
  <div class="home-page">
    <div class="page-header">
      <h2 class="page-title">工作台</h2>
    </div>

    <div class="stats-grid">
      <div v-for="stat in stats" :key="stat.label" class="stat-card">
        <div class="stat-icon" :style="{ background: stat.color + '15', color: stat.color }">
          <el-icon><component :is="stat.icon" /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ stat.value }}</div>
          <div class="stat-label">{{ stat.label }}</div>
        </div>
      </div>
    </div>

    <div class="content-grid">
      <div class="card">
        <div class="card-header">
          <h3 class="card-title">最近需求</h3>
          <router-link to="/requirements" class="card-action">查看全部</router-link>
        </div>
        <div class="card-body">
          <div v-for="req in recentRequirements" :key="req.id" class="requirement-item">
            <div class="requirement-info">
              <div class="requirement-title">{{ req.title }}</div>
              <div class="requirement-meta">{{ req.project }}</div>
            </div>
            <span class="requirement-status" :class="req.statusClass">{{ req.status }}</span>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-header">
          <h3 class="card-title">快捷操作</h3>
        </div>
        <div class="card-body">
          <div class="quick-actions">
            <router-link to="/requirements" class="quick-action-item">
              <el-icon><Document /></el-icon>
              <span>新建需求</span>
            </router-link>
            <router-link to="/tasks" class="quick-action-item">
              <el-icon><Finished /></el-icon>
              <span>我的任务</span>
            </router-link>
            <router-link to="/statistics" class="quick-action-item">
              <el-icon><DataAnalysis /></el-icon>
              <span>数据统计</span>
            </router-link>
          </div>
        </div>
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
  display: flex;
  align-items: center;
  gap: 16px;
  box-shadow: var(--shadow-sm);
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--gray-800);
  line-height: 1;
}

.stat-label {
  font-size: 13px;
  color: var(--gray-500);
  margin-top: 4px;
}

.content-grid {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 16px;
}

@media (max-width: 768px) {
  .content-grid {
    grid-template-columns: 1fr;
  }
}

.card {
  background: white;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.card-header {
  padding: 16px 20px;
  border-bottom: 1px solid var(--gray-100);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--gray-800);
  margin: 0;
}

.card-action {
  font-size: 13px;
  color: var(--primary);
  text-decoration: none;
}

.card-action:hover {
  text-decoration: underline;
}

.card-body {
  padding: 12px 20px;
}

.requirement-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 0;
  border-bottom: 1px solid var(--gray-50);
}

.requirement-item:last-child {
  border-bottom: none;
}

.requirement-title {
  font-size: 14px;
  color: var(--gray-700);
  margin-bottom: 2px;
}

.requirement-meta {
  font-size: 12px;
  color: var(--gray-400);
}

.requirement-status {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 12px;
  font-weight: 500;
}

.status-info {
  background: #e0e7ff;
  color: #4f46e5;
}

.status-warning {
  background: #fef3c7;
  color: #d97706;
}

.status-primary {
  background: #dbeafe;
  color: #2563eb;
}

.quick-actions {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.quick-action-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 16px;
  background: var(--gray-50);
  border-radius: var(--radius-md);
  color: var(--gray-600);
  text-decoration: none;
  font-size: 13px;
  transition: all 0.2s ease;
}

.quick-action-item:hover {
  background: var(--primary-light);
  color: var(--primary);
}

.quick-action-item .el-icon {
  font-size: 24px;
}
</style>
