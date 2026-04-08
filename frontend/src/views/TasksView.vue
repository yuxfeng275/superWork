<script setup lang="ts">
import { ref } from 'vue'

const tasks = ref([
  { id: '1', title: '数据库设计', type: '开发', assignee: '张三', estimatedHours: 8, status: '已完成' },
  { id: '2', title: '后端API开发', type: '开发', assignee: '李四', estimatedHours: 24, status: '进行中' },
  { id: '3', title: '前端页面开发', type: '开发', assignee: '王五', estimatedHours: 32, status: '待开始' },
  { id: '4', title: '接口联调', type: '开发', assignee: '张三', estimatedHours: 8, status: '待开始' }
])
</script>

<template>
  <div class="tasks-page">
    <div class="page-header">
      <h2 class="page-title">任务管理</h2>
    </div>

    <div class="tasks-list">
      <div v-for="task in tasks" :key="task.id" class="task-card">
        <div class="task-checkbox">
          <el-icon v-if="task.status === '已完成'" color="#10b981"><Check /></el-icon>
          <div v-else class="checkbox-empty"></div>
        </div>
        <div class="task-info">
          <span class="task-title" :class="{ 'task-done': task.status === '已完成' }">{{ task.title }}</span>
          <div class="task-meta">
            <span class="meta-item">{{ task.type }}</span>
            <span class="meta-dot">·</span>
            <span class="meta-item">{{ task.assignee }}</span>
            <span class="meta-dot">·</span>
            <span class="meta-item">{{ task.estimatedHours }}h</span>
          </div>
        </div>
        <span class="task-status" :class="task.status === '已完成' ? 'status-done' : task.status === '进行中' ? 'status-progress' : 'status-pending'">
          {{ task.status }}
        </span>
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

.tasks-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.task-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 20px;
  background: white;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}

.task-checkbox {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.checkbox-empty {
  width: 20px;
  height: 20px;
  border: 2px solid var(--gray-300);
  border-radius: 4px;
}

.task-info {
  flex: 1;
}

.task-title {
  display: block;
  font-size: 15px;
  font-weight: 500;
  color: var(--gray-800);
  margin-bottom: 4px;
}

.task-done {
  text-decoration: line-through;
  color: var(--gray-400);
}

.task-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--gray-400);
}

.task-status {
  font-size: 12px;
  font-weight: 500;
  padding: 4px 12px;
  border-radius: 12px;
}

.status-done {
  background: #d1fae5;
  color: #059669;
}

.status-progress {
  background: #fef3c7;
  color: #d97706;
}

.status-pending {
  background: var(--gray-100);
  color: var(--gray-600);
}
</style>
