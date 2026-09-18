<script setup lang="ts">
import { ArrowRight, Location, Refresh, User, VideoCamera } from '@element-plus/icons-vue'
import { SCHEDULE_SOURCE_COLORS, SCHEDULE_SOURCE_LABELS } from '@/constants/schedule'
import type { ScheduleEventView } from '@/types/schedule'

const props = defineProps<{
  item: ScheduleEventView
}>()

const emit = defineEmits<{
  (event: 'open', item: ScheduleEventView): void
}>()
</script>

<template>
  <button type="button" class="event-row" @click="emit('open', props.item)">
    <span class="row-time" :class="{ 'is-allday': props.item.allDay }">{{ props.item.rangeLabel }}</span>
    <span class="row-main">
      <span class="row-title-line">
        <span class="row-title">{{ props.item.title }}</span>
        <span class="row-source" :style="{ color: SCHEDULE_SOURCE_COLORS[props.item.source] }">
          {{ SCHEDULE_SOURCE_LABELS[props.item.source] }}
        </span>
        <span v-if="props.item.recurring" class="row-flag"><el-icon><Refresh /></el-icon>周期</span>
        <span v-if="props.item.event.meetingCode" class="row-flag is-online"><el-icon><VideoCamera /></el-icon>线上</span>
      </span>
      <span class="row-meta">
        <span v-if="props.item.event.location" class="row-meta-item">
          <el-icon><Location /></el-icon>{{ props.item.event.location }}
        </span>
        <span v-if="props.item.event.organizer" class="row-meta-item">
          <el-icon><User /></el-icon>{{ props.item.event.organizer }}
        </span>
        <span v-if="props.item.participants.length > 0" class="row-meta-item">
          <el-icon><User /></el-icon>参与 {{ props.item.participants.length }} 人
        </span>
      </span>
    </span>
    <el-icon class="row-arrow"><ArrowRight /></el-icon>
  </button>
</template>

<style scoped>
.event-row {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-md);
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.event-row:hover {
  border-color: var(--primary);
  box-shadow: var(--shadow-sm);
}

.row-time {
  flex-shrink: 0;
  width: 104px;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: var(--gray-700);
}

.row-time.is-allday {
  color: var(--primary);
  font-weight: 600;
}

.row-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  flex: 1;
}

.row-title-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.row-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--gray-800);
  word-break: break-word;
}

.row-source {
  flex-shrink: 0;
  padding: 1px 8px;
  border: 1px solid currentcolor;
  border-radius: 999px;
  font-size: 11px;
  line-height: 18px;
}

.row-flag {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--gray-100);
  color: var(--gray-600);
  font-size: 11px;
  line-height: 18px;
}

.row-flag.is-online {
  background: var(--primary-light);
  color: var(--primary);
}

.row-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-size: 12px;
  color: var(--gray-500);
}

.row-meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.row-meta-item .el-icon {
  flex-shrink: 0;
}

.row-arrow {
  flex-shrink: 0;
  margin-top: 2px;
  color: var(--gray-400);
}
</style>
