<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowLeft, ArrowRight, Calendar, Clock, Refresh } from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import { splitHintLinks } from '@/utils/hint-links'
import ScheduleEventItem from '@/components/ScheduleEventItem.vue'
import {
  MEETING_STATUS_LABELS,
  SCHEDULE_SOURCE_COLORS,
  SCHEDULE_SOURCE_FILTERS,
  SCHEDULE_SOURCE_LABELS,
  SCHEDULE_SOURCE_TINTS,
  SCHEDULE_SOURCES,
  type ScheduleSourceFilter
} from '@/constants/schedule'
import type { ScheduleEvent, ScheduleEventView, ScheduleSource } from '@/types/schedule'

type ViewMode = 'calendar' | 'list'

/** 日历每格最多展示的事件条数，超出折叠为「+N」。 */
const MAX_CHIPS = 3
const WEEKDAY_LABELS = ['一', '二', '三', '四', '五', '六', '日']
/** 墙上时间：日期 + 可选时刻（后端固定 yyyy-MM-dd HH:mm:ss，缺时刻按 00:00 处理）。 */
const WALL_CLOCK_PATTERN = /^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2})(?::(\d{2}))?)?/

const viewMode = ref<ViewMode>('calendar')
const sourceFilter = ref<ScheduleSourceFilter>('ALL')
const events = ref<ScheduleEvent[]>([])
const hints = ref<string[]>([])
const rangeStart = ref('')
const rangeEnd = ref('')
const loading = ref(false)
const loadError = ref('')
const monthCursor = ref(new Date(new Date().getFullYear(), new Date().getMonth(), 1))

const dayDialogVisible = ref(false)
const dayDialogKey = ref('')
const detailVisible = ref(false)
const activeEvent = ref<ScheduleEventView | null>(null)

const errorText = (error: unknown, fallback: string) =>
  error instanceof Error && error.message ? error.message : fallback

/** 墙上时间 → 本地日期对象 + 展示时刻。禁止 new Date(str)：会被按 UTC 解析而整体偏移时区。 */
const parseWallClock = (value?: string | null) => {
  const match = value ? WALL_CLOCK_PATTERN.exec(value.trim()) : null
  if (!match) return null
  const [, year, month, day, hour = '00', minute = '00', second = '00'] = match
  return {
    dayKey: `${year}-${month}-${day}`,
    date: new Date(Number(year), Number(month) - 1, Number(day)),
    clock: `${hour}:${minute}`,
    seconds: Number(hour) * 3600 + Number(minute) * 60 + Number(second)
  }
}

/** Date → 墙上日期（yyyy-MM-dd），与后端 from/to 同口径 */
const toDayKey = (date: Date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`

const shiftDays = (date: Date, days: number) =>
  new Date(date.getFullYear(), date.getMonth(), date.getDate() + days)

/** 日期标题：'9 月 17 日 周四' */
const dayTitle = (dayKey: string) => {
  const parsed = parseWallClock(dayKey)
  if (!parsed) return dayKey
  const weekday = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][parsed.date.getDay()]
  return `${parsed.date.getMonth() + 1} 月 ${parsed.date.getDate()} 日 ${weekday}`
}

/** 秒 → 中文时长：本地会议 end = 日期 + 录音时长，可据此还原 */
const formatDuration = (seconds: number) => {
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (hours > 0) return `${hours} 小时 ${minutes} 分`
  if (minutes > 0) return `${minutes} 分 ${seconds % 60} 秒`
  return `${seconds} 秒`
}

/** 事件 → 视图模型：墙上时间解析一次，日历分组与列表排序共用 */
const toEventView = (event: ScheduleEvent): ScheduleEventView => {
  const start = parseWallClock(event.start)
  const end = parseWallClock(event.end)
  const allDay = event.allDay === true || start === null
  const sameDayDuration = start && end && end.dayKey === start.dayKey && end.seconds > start.seconds
    ? end.seconds - start.seconds
    : null
  return {
    event,
    id: event.id,
    source: event.source,
    title: event.title || '(无标题)',
    dayKey: start ? start.dayKey : String(event.start || '').slice(0, 10),
    timeLabel: allDay || !start ? '全天' : start.clock,
    rangeLabel: allDay || !start ? '全天' : `${start.clock} – ${end ? end.clock : start.clock}`,
    // 全天事件排当天最前
    daySeconds: allDay || !start ? -1 : start.seconds,
    allDay,
    recurring: event.recurring === true,
    participants: Array.isArray(event.participants) ? event.participants : [],
    durationSeconds: event.source === 'MEETING' ? sameDayDuration : null
  }
}

const monthStart = computed(() => new Date(monthCursor.value.getFullYear(), monthCursor.value.getMonth(), 1))
const monthEnd = computed(() => new Date(monthCursor.value.getFullYear(), monthCursor.value.getMonth() + 1, 0))
const monthPrefix = computed(() => toDayKey(monthStart.value).slice(0, 7))
const monthLabel = computed(() => `${monthStart.value.getFullYear()} 年 ${monthStart.value.getMonth() + 1} 月`)
const todayKey = computed(() => toDayKey(new Date()))

/** 取数区间：日历视图前后各多取一周（跨月周次不断档），列表模式只取当月。 */
const queryRange = computed(() => viewMode.value === 'calendar'
  ? { from: toDayKey(shiftDays(monthStart.value, -7)), to: toDayKey(shiftDays(monthEnd.value, 7)) }
  : { from: toDayKey(monthStart.value), to: toDayKey(monthEnd.value) })

const activeSources = computed<ScheduleSource[]>(() =>
  sourceFilter.value === 'ALL' ? SCHEDULE_SOURCES : [sourceFilter.value]
)

const eventViews = computed(() =>
  events.value
    .map(toEventView)
    .sort((a, b) => (a.dayKey === b.dayKey ? a.daySeconds - b.daySeconds : a.dayKey.localeCompare(b.dayKey)))
)

const eventsByDay = computed(() => {
  const map = new Map<string, ScheduleEventView[]>()
  for (const view of eventViews.value) {
    const bucket = map.get(view.dayKey)
    if (bucket) bucket.push(view)
    else map.set(view.dayKey, [view])
  }
  return map
})

const monthEventCount = computed(() =>
  eventViews.value.filter(view => view.dayKey.startsWith(monthPrefix.value)).length
)

interface CalendarCell {
  key: string
  dayNumber: number
  inMonth: boolean
  isToday: boolean
  items: ScheduleEventView[]
  overflow: number
}

/** 月网格：周一为一周起始，固定 7 的整数格（4~6 周） */
const calendarCells = computed<CalendarCell[]>(() => {
  const start = monthStart.value
  const end = monthEnd.value
  const offset = (start.getDay() + 6) % 7
  const gridStart = shiftDays(start, -offset)
  const cellCount = Math.ceil((offset + end.getDate()) / 7) * 7
  const cells: CalendarCell[] = []
  for (let index = 0; index < cellCount; index += 1) {
    const date = shiftDays(gridStart, index)
    const key = toDayKey(date)
    const dayEvents = eventsByDay.value.get(key) ?? []
    cells.push({
      key,
      dayNumber: date.getDate(),
      inMonth: date.getMonth() === start.getMonth(),
      isToday: key === todayKey.value,
      items: dayEvents.slice(0, MAX_CHIPS),
      overflow: Math.max(0, dayEvents.length - MAX_CHIPS)
    })
  }
  return cells
})

const listGroups = computed(() => {
  const groups: Array<{ key: string; items: ScheduleEventView[] }> = []
  for (const view of eventViews.value) {
    const last = groups[groups.length - 1]
    if (last && last.key === view.dayKey) last.items.push(view)
    else groups.push({ key: view.dayKey, items: [view] })
  }
  return groups
})

/** hint 是多行文本：按行拆分后再切链接片段，逐段渲染（不经过 v-html） */
const hintBlocks = computed(() =>
  hints.value.map(hint => hint
    .split(/\r?\n/)
    .map(line => line.trim())
    .filter(line => line.length > 0)
    .map(line => splitHintLinks(line)))
)

const dayDialogEvents = computed(() => eventsByDay.value.get(dayDialogKey.value) ?? [])
const dayDialogTitle = computed(() => `${dayTitle(dayDialogKey.value)} · ${dayDialogEvents.value.length} 项日程`)
const activeStatusLabel = computed(() => {
  const status = activeEvent.value?.event.status
  if (!status) return ''
  return MEETING_STATUS_LABELS[status] ?? status
})

const loadEvents = async () => {
  loading.value = true
  loadError.value = ''
  const { from, to } = queryRange.value
  try {
    const payload = await api.getScheduleEvents({ from, to, sources: activeSources.value })
    events.value = Array.isArray(payload?.events) ? payload.events : []
    hints.value = Array.isArray(payload?.hints) ? payload.hints.filter(hint => Boolean(hint)) : []
    rangeStart.value = payload?.rangeStart || from
    rangeEnd.value = payload?.rangeEnd || to
  } catch (error: unknown) {
    events.value = []
    hints.value = []
    loadError.value = errorText(error, '日程加载失败')
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

const shiftMonth = (delta: number) => {
  monthCursor.value = new Date(monthStart.value.getFullYear(), monthStart.value.getMonth() + delta, 1)
}

const goToday = () => {
  monthCursor.value = new Date(new Date().getFullYear(), new Date().getMonth(), 1)
}

const openDay = (key: string) => {
  dayDialogKey.value = key
  dayDialogVisible.value = true
}

const openEvent = (item: ScheduleEventView) => {
  activeEvent.value = item
  detailVisible.value = true
}

watch([viewMode, sourceFilter, monthCursor], () => {
  void loadEvents()
})

onMounted(() => {
  void loadEvents()
})
</script>

<template>
  <div class="schedule-page" v-loading="loading">
    <header class="page-head">
      <div>
        <span class="eyebrow">SCHEDULE</span>
        <h2>日程</h2>
        <p>本地会议与企微日程合并视图：日历视图按月浏览、列表模式按天看时间轴；企微日程仅支持当天前后 30 天。</p>
      </div>
      <div class="head-actions">
        <el-radio-group v-model="viewMode" size="small" aria-label="展示模式">
          <el-radio-button value="calendar"><el-icon><Calendar /></el-icon> 日历视图</el-radio-button>
          <el-radio-button value="list"><el-icon><Clock /></el-icon> 列表模式</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" aria-label="刷新日程" @click="loadEvents" />
      </div>
    </header>

    <div class="toolbar">
      <div class="filter-pills" aria-label="来源筛选">
        <button
          v-for="option in SCHEDULE_SOURCE_FILTERS"
          :key="option.value"
          type="button"
          class="filter-pill"
          :class="{ active: sourceFilter === option.value }"
          @click="sourceFilter = option.value"
        >{{ option.label }}</button>
      </div>
      <div class="month-nav">
        <el-button size="small" :icon="ArrowLeft" aria-label="上一月" @click="shiftMonth(-1)" />
        <span class="month-label">{{ monthLabel }}</span>
        <el-button size="small" :icon="ArrowRight" aria-label="下一月" @click="shiftMonth(1)" />
        <el-button size="small" @click="goToday">今天</el-button>
      </div>
    </div>

    <p class="range-line">数据范围 {{ rangeStart || '—' }} ~ {{ rangeEnd || '—' }}（闭区间，按天）</p>

    <div v-if="hintBlocks.length > 0" class="hint-stack">
      <el-alert
        v-for="(lines, hintIndex) in hintBlocks"
        :key="hintIndex"
        type="warning"
        :closable="false"
        show-icon
        title="企微侧提示"
      >
        <p v-for="(segments, lineIndex) in lines" :key="lineIndex" class="hint-line">
          <template v-for="(segment, segmentIndex) in segments" :key="segmentIndex">
            <a v-if="segment.href" :href="segment.href" target="_blank" rel="noopener noreferrer">{{ segment.text }}</a>
            <template v-else>{{ segment.text }}</template>
          </template>
        </p>
      </el-alert>
    </div>

    <p v-if="loadError" class="load-error">{{ loadError }}</p>

    <template v-if="viewMode === 'calendar'">
      <div class="calendar-legend">
        <span v-for="source in SCHEDULE_SOURCES" :key="source" class="legend-item">
          <i class="legend-dot" :style="{ background: SCHEDULE_SOURCE_COLORS[source] }" />{{ SCHEDULE_SOURCE_LABELS[source] }}
        </span>
        <span class="legend-note">周一为一周起始 · 每格最多 3 条，点「+N」看当天全部</span>
      </div>

      <p v-if="!loading && !loadError && monthEventCount === 0" class="month-empty-tip">本月暂无日程</p>

      <div class="calendar-grid">
        <div class="weekday-row">
          <span v-for="label in WEEKDAY_LABELS" :key="label">{{ label }}</span>
        </div>
        <div class="day-grid">
          <div
            v-for="cell in calendarCells"
            :key="cell.key"
            class="day-cell"
            :class="{ outside: !cell.inMonth, today: cell.isToday }"
          >
            <button type="button" class="day-head" :aria-label="`查看 ${cell.key} 全部日程`" @click="openDay(cell.key)">
              <span class="day-number">{{ cell.dayNumber }}</span>
              <span v-if="cell.isToday" class="today-tag">今天</span>
              <span v-if="cell.items.length + cell.overflow > 0" class="day-count">{{ cell.items.length + cell.overflow }}</span>
            </button>
            <div class="day-events">
              <button
                v-for="item in cell.items"
                :key="item.id"
                type="button"
                class="event-chip"
                :class="{ 'is-allday': item.allDay }"
                :style="{ '--chip-color': SCHEDULE_SOURCE_COLORS[item.source], '--chip-bg': SCHEDULE_SOURCE_TINTS[item.source] }"
                :title="`${SCHEDULE_SOURCE_LABELS[item.source]} ${item.timeLabel} ${item.title}`"
                @click="openEvent(item)"
              >
                <span class="chip-time">{{ item.timeLabel }}</span>
                <span class="chip-title">{{ item.title }}</span>
                <el-icon v-if="item.recurring" class="chip-flag" title="周期日程"><Refresh /></el-icon>
              </button>
              <button v-if="cell.overflow > 0" type="button" class="chip-more" @click="openDay(cell.key)">
                +{{ cell.overflow }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </template>

    <template v-else>
      <div v-if="listGroups.length > 0" class="list-block">
        <article v-for="group in listGroups" :key="group.key" class="day-group">
          <header class="day-group-head">
            <h3>{{ dayTitle(group.key) }}</h3>
            <span v-if="group.key === todayKey" class="today-tag">今天</span>
            <span class="day-group-count">{{ group.items.length }} 项</span>
          </header>
          <div class="day-list">
            <ScheduleEventItem v-for="item in group.items" :key="item.id" :item="item" @open="openEvent" />
          </div>
        </article>
      </div>
      <el-empty v-else-if="!loading && !loadError" description="本月暂无日程" />
    </template>

    <el-dialog v-model="dayDialogVisible" :title="dayDialogTitle" width="560px" append-to-body>
      <div class="day-list">
        <ScheduleEventItem v-for="item in dayDialogEvents" :key="item.id" :item="item" @open="openEvent" />
      </div>
      <el-empty v-if="dayDialogEvents.length === 0" description="当天暂无日程" :image-size="72" />
    </el-dialog>

    <el-drawer
      v-model="detailVisible"
      :title="activeEvent ? activeEvent.title : '日程详情'"
      size="480px"
      append-to-body
      destroy-on-close
    >
      <div v-if="activeEvent" class="detail-body">
        <div class="detail-tags">
          <span class="detail-source" :style="{ color: SCHEDULE_SOURCE_COLORS[activeEvent.source] }">
            {{ SCHEDULE_SOURCE_LABELS[activeEvent.source] }}
          </span>
          <el-tag v-if="activeEvent.allDay" size="small" type="info" effect="light">全天</el-tag>
          <el-tag v-if="activeEvent.recurring" size="small" effect="light">周期日程</el-tag>
          <el-tag v-if="activeEvent.event.meetingCode" size="small" type="primary" effect="light">线上会议</el-tag>
          <el-tag v-if="activeStatusLabel" size="small" effect="light">{{ activeStatusLabel }}</el-tag>
        </div>

        <dl class="detail-list">
          <div class="detail-row">
            <dt>时间</dt>
            <dd>{{ activeEvent.allDay ? `${activeEvent.dayKey}（全天）` : `${activeEvent.dayKey} ${activeEvent.rangeLabel}` }}</dd>
          </div>
          <div v-if="activeEvent.durationSeconds" class="detail-row">
            <dt>录音时长</dt>
            <dd>{{ formatDuration(activeEvent.durationSeconds) }}</dd>
          </div>
          <div v-if="activeEvent.event.location" class="detail-row">
            <dt>地点</dt>
            <dd>{{ activeEvent.event.location }}</dd>
          </div>
          <div v-if="activeEvent.event.organizer" class="detail-row">
            <dt>组织者</dt>
            <dd>{{ activeEvent.event.organizer }}</dd>
          </div>
          <div v-if="activeEvent.event.calendarName" class="detail-row">
            <dt>日历</dt>
            <dd>{{ activeEvent.event.calendarName }}</dd>
          </div>
          <div v-if="activeEvent.participants.length > 0" class="detail-row">
            <dt>参与人</dt>
            <dd>{{ activeEvent.participants.join('、') }}（{{ activeEvent.participants.length }} 人）</dd>
          </div>
          <div v-if="activeEvent.event.meetingCode" class="detail-row">
            <dt>会议号</dt>
            <dd class="detail-mono">{{ activeEvent.event.meetingCode }}</dd>
          </div>
          <div v-if="activeEvent.event.meetingLink" class="detail-row">
            <dt>入会链接</dt>
            <dd>
              <a :href="activeEvent.event.meetingLink" target="_blank" rel="noopener noreferrer">
                {{ activeEvent.event.meetingLink }}
              </a>
            </dd>
          </div>
          <div v-if="activeEvent.event.description" class="detail-row">
            <dt>描述</dt>
            <dd class="detail-desc">{{ activeEvent.event.description }}</dd>
          </div>
        </dl>

        <p v-if="activeEvent.source === 'MEETING'" class="detail-note">
          录音、转写与 AI 纪要请在会议模块查看（新版控制台 /meetings/{{ activeEvent.event.meetingId }}）。
        </p>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.schedule-page {
  padding: 24px 28px 48px;
  display: flex;
  flex-direction: column;
  gap: 14px;
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
  color: var(--gray-400);
}

.page-head h2 {
  margin: 4px 0 6px;
  font-size: 22px;
  font-weight: 700;
  color: var(--gray-800);
}

.page-head p {
  margin: 0;
  font-size: 13px;
  color: var(--gray-500);
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}

.filter-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.filter-pill {
  border: 1px solid var(--gray-200);
  background: #fff;
  border-radius: 999px;
  padding: 4px 14px;
  font-size: 13px;
  color: var(--gray-500);
  cursor: pointer;
}

.filter-pill.active {
  border-color: var(--primary);
  color: var(--primary);
  background: var(--primary-light);
}

.month-nav {
  display: flex;
  align-items: center;
  gap: 8px;
}

.month-label {
  min-width: 96px;
  text-align: center;
  font-size: 14px;
  font-weight: 600;
  color: var(--gray-700);
}

.range-line {
  margin: 0;
  font-size: 12px;
  color: var(--gray-400);
}

.hint-stack {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.hint-line {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-word;
}

.hint-line a {
  color: var(--primary);
  word-break: break-all;
}

.load-error {
  margin: 0;
  font-size: 13px;
  color: var(--danger);
}

.calendar-legend {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 18px;
  font-size: 12px;
  color: var(--gray-500);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.legend-note {
  color: var(--gray-400);
}

.month-empty-tip {
  margin: 0;
  padding: 8px 12px;
  border: 1px dashed var(--gray-200);
  border-radius: var(--radius-md);
  background: var(--gray-50);
  font-size: 13px;
  color: var(--gray-500);
}

.calendar-grid {
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-lg);
  background: #fff;
  overflow: hidden;
}

.weekday-row {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  background: var(--gray-50);
}

.weekday-row span {
  padding: 8px 10px;
  font-size: 12px;
  font-weight: 600;
  color: var(--gray-500);
  text-align: center;
}

.day-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
}

.day-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 118px;
  padding: 6px 8px 8px;
  border-top: 1px solid var(--gray-200);
  border-right: 1px solid var(--gray-200);
}

.day-cell:nth-child(7n) {
  border-right: none;
}

.day-cell.outside {
  background: var(--gray-50);
}

.day-cell.outside .day-number {
  color: var(--gray-400);
}

.day-cell.today {
  background: var(--primary-light);
}

.day-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
}

.day-number {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-700);
  font-variant-numeric: tabular-nums;
}

.today-tag {
  padding: 0 6px;
  border-radius: 999px;
  background: var(--primary);
  color: #fff;
  font-size: 10px;
  line-height: 16px;
}

.day-count {
  margin-left: auto;
  font-size: 11px;
  color: var(--gray-400);
}

.day-events {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.event-chip {
  display: flex;
  align-items: center;
  gap: 5px;
  width: 100%;
  padding: 2px 5px;
  border: none;
  border-left: 3px solid var(--chip-color, var(--primary));
  border-radius: var(--radius-sm);
  background: var(--chip-bg, var(--primary-light));
  text-align: left;
  cursor: pointer;
  overflow: hidden;
}

.event-chip:hover {
  filter: brightness(0.96);
}

.chip-time {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--gray-600);
  font-variant-numeric: tabular-nums;
}

.event-chip.is-allday .chip-time {
  color: var(--chip-color, var(--primary));
  font-weight: 600;
}

.chip-title {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--gray-800);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chip-flag {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--chip-color, var(--primary));
}

.chip-more {
  align-self: flex-start;
  padding: 1px 6px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  font-size: 11px;
  color: var(--gray-500);
  cursor: pointer;
}

.chip-more:hover {
  color: var(--primary);
}

.list-block {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.day-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.day-group-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.day-group-head h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
  color: var(--gray-800);
}

.day-group-count {
  font-size: 12px;
  color: var(--gray-400);
}

.day-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.detail-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.detail-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.detail-source {
  padding: 1px 9px;
  border: 1px solid currentcolor;
  border-radius: 999px;
  font-size: 12px;
  line-height: 20px;
}

.detail-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 0;
}

.detail-row {
  display: flex;
  gap: 12px;
  font-size: 13px;
}

.detail-row dt {
  flex-shrink: 0;
  width: 68px;
  color: var(--gray-500);
}

.detail-row dd {
  margin: 0;
  min-width: 0;
  color: var(--gray-800);
  word-break: break-word;
}

.detail-row dd a {
  color: var(--primary);
  word-break: break-all;
}

.detail-mono {
  font-variant-numeric: tabular-nums;
}

.detail-desc {
  white-space: pre-line;
}

.detail-note {
  margin: 0;
  padding: 8px 12px;
  border-radius: var(--radius-md);
  background: var(--gray-50);
  font-size: 12px;
  line-height: 1.6;
  color: var(--gray-500);
}

@media (max-width: 900px) {
  .schedule-page {
    padding: 16px 14px 32px;
  }

  .day-cell {
    min-height: 92px;
    padding: 4px 5px 6px;
  }

  .chip-time {
    display: none;
  }

  /* 窄屏隐藏时刻但保留「全天」标记 */
  .event-chip.is-allday .chip-time {
    display: inline;
  }
}
</style>
