import {
  EnvironmentOutlined,
  LeftOutlined,
  ReloadOutlined,
  RightOutlined,
  SyncOutlined,
  TeamOutlined,
  UserOutlined,
  VideoCameraOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Segmented,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import customParseFormat from 'dayjs/plugin/customParseFormat';
import type { KeyboardEvent, ReactNode } from 'react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  type MeetingStatus,
  type ScheduleEvent,
  type ScheduleEventSource,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

dayjs.extend(customParseFormat);

/** 后端时间一律为「墙上时间」字符串，必须按格式解析（new Date 会按时区偏移）。 */
const WALL_TIME = 'YYYY-MM-DD HH:mm:ss';
const DATE_KEY = 'YYYY-MM-DD';

/** 单元格最多展示的日程条数，超出折叠为 +N（点击日期在右侧展开）。 */
const MAX_CELL_CHIPS = 3;

const WEEKDAY_LABELS = ['一', '二', '三', '四', '五', '六', '日'];
const WEEKDAY_NAMES = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];

type ViewMode = 'calendar' | 'list';
type SourceFilter = 'ALL' | ScheduleEventSource;

const SOURCE_META: Record<
  ScheduleEventSource,
  { label: string; color: string; tone: string }
> = {
  MEETING: { label: '本地会议', color: 'blue', tone: 'meeting' },
  WECOM_SCHEDULE: { label: '企微日程', color: 'green', tone: 'schedule' },
};

const ALL_SOURCES = Object.keys(SOURCE_META) as ScheduleEventSource[];

const SOURCE_OPTIONS: Array<{ label: string; value: SourceFilter }> = [
  { label: '全部来源', value: 'ALL' },
  ...ALL_SOURCES.map((source) => ({
    label: SOURCE_META[source].label,
    value: source as SourceFilter,
  })),
];

/** 本地会议状态标签（与会议模块口径一致，未知状态回退原文）。 */
const MEETING_STATUS_LABEL: Record<string, string> = {
  UPLOADED: '待处理',
  TRANSCRIBING: '转写中',
  SUMMARIZING: '总结中',
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  FAILED: '失败',
};

const parseWallTime = (value: string) => dayjs(value, WALL_TIME);

/** 周一为一周起始。 */
const startOfWeekMonday = (date: Dayjs) => {
  const weekday = date.day();
  return date.startOf('day').subtract(weekday === 0 ? 6 : weekday - 1, 'day');
};

/** 月网格：补齐首周与末周，周一为第一列。 */
const buildMonthGrid = (month: Dayjs): Dayjs[][] => {
  const gridStart = startOfWeekMonday(month.startOf('month'));
  const gridEnd = startOfWeekMonday(month.endOf('month')).add(6, 'day');
  const weeks: Dayjs[][] = [];
  let cursor = gridStart;
  while (!cursor.isAfter(gridEnd, 'day')) {
    weeks.push(Array.from({ length: 7 }, (_, index) => cursor.add(index, 'day')));
    cursor = cursor.add(7, 'day');
  }
  return weeks;
};

type DayGroup = { key: string; day: Dayjs; events: ScheduleEvent[] };

/** 按天分组（跨天与全天事件归入开始日）；全天事件排在最前，其余按开始时刻。 */
const groupEventsByDay = (events: ScheduleEvent[]): DayGroup[] => {
  const groups = new Map<string, DayGroup>();
  [...events]
    .sort(
      (a, b) =>
        Number(b.allDay) - Number(a.allDay) ||
        parseWallTime(a.start).valueOf() - parseWallTime(b.start).valueOf(),
    )
    .forEach((event) => {
      const start = parseWallTime(event.start);
      const key = start.format(DATE_KEY);
      const existing = groups.get(key);
      if (existing) existing.events.push(event);
      else groups.set(key, { key, day: start, events: [event] });
    });
  return [...groups.values()].sort((a, b) => a.day.valueOf() - b.day.valueOf());
};

const timeRangeText = (event: ScheduleEvent) => {
  if (event.allDay) return '全天';
  const start = parseWallTime(event.start);
  const end = parseWallTime(event.end);
  return `${start.format('HH:mm')} - ${end.format('HH:mm')}`;
};

/** hint 内嵌 markdown 链接（企微授权引导），逐字展示并把链接渲染为可点击外链。 */
const renderHint = (hint: string): ReactNode => {
  const nodes: ReactNode[] = [];
  const pattern = /\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g;
  let cursor = 0;
  let match = pattern.exec(hint);
  while (match) {
    if (match.index > cursor) nodes.push(hint.slice(cursor, match.index));
    nodes.push(
      <a
        key={`link-${match.index}`}
        href={match[2]}
        target="_blank"
        rel="noopener noreferrer"
      >
        {match[1]}
      </a>,
    );
    cursor = match.index + match[0].length;
    match = pattern.exec(hint);
  }
  if (cursor < hint.length) nodes.push(hint.slice(cursor));
  return nodes;
};

function ScheduleEventRow({
  event,
  onOpen,
}: {
  event: ScheduleEvent;
  onOpen: (event: ScheduleEvent) => void;
}) {
  const meta = SOURCE_META[event.source];
  const handleKeyDown = (keyboardEvent: KeyboardEvent<HTMLDivElement>) => {
    if (keyboardEvent.key === 'Enter' || keyboardEvent.key === ' ') {
      keyboardEvent.preventDefault();
      onOpen(event);
    }
  };
  return (
    <div
      className={`sw-schedule-event sw-schedule-event-${meta.tone}`}
      role="button"
      tabIndex={0}
      onClick={() => onOpen(event)}
      onKeyDown={handleKeyDown}
    >
      <div className="sw-schedule-event-time">
        {timeRangeText(event)}
        {event.recurring && (
          <SyncOutlined className="sw-schedule-recurring" title="周期日程" />
        )}
      </div>
      <div className="sw-schedule-event-main">
        <div className="sw-schedule-event-title">{event.title}</div>
        <div className="sw-schedule-event-meta">
          <Tag color={meta.color} className="sw-schedule-source-tag">
            {meta.label}
          </Tag>
          {event.allDay && <Tag className="sw-schedule-allday-tag">全天</Tag>}
          {event.location && (
            <span className="sw-schedule-event-meta-item">
              <EnvironmentOutlined /> {event.location}
            </span>
          )}
          {event.organizer && (
            <span className="sw-schedule-event-meta-item">
              <UserOutlined /> {event.organizer}
            </span>
          )}
          {Boolean(event.participants?.length) && (
            <span className="sw-schedule-event-meta-item">
              <TeamOutlined /> {event.participants.length} 人
            </span>
          )}
          {event.meetingCode && (
            <span className="sw-schedule-event-meta-item">
              <VideoCameraOutlined /> {event.meetingCode}
            </span>
          )}
          {event.status && (
            <span className="sw-schedule-event-meta-item">
              状态：{MEETING_STATUS_LABEL[event.status] || event.status}
            </span>
          )}
        </div>
      </div>
      {event.source === 'MEETING' && (
        <RightOutlined className="sw-schedule-event-arrow" />
      )}
    </div>
  );
}

export default function SchedulePage() {
  const [mode, setMode] = useState<ViewMode>('calendar');
  const [sourceFilter, setSourceFilter] = useState<SourceFilter>('ALL');
  const [month, setMonth] = useState<Dayjs>(() => dayjs().startOf('month'));
  const [selectedDate, setSelectedDate] = useState<string>(() =>
    dayjs().format(DATE_KEY),
  );
  const [events, setEvents] = useState<ScheduleEvent[]>([]);
  const [hints, setHints] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState<ScheduleEvent>();
  const requestSeq = useRef(0);

  const grid = useMemo(() => buildMonthGrid(month), [month]);
  const visibleRange = useMemo(() => {
    if (mode === 'list')
      return { start: month.startOf('month'), end: month.endOf('month') };
    return { start: grid[0][0], end: grid[grid.length - 1][6] };
  }, [grid, mode, month]);

  const load = useCallback(async () => {
    const seq = requestSeq.current + 1;
    requestSeq.current = seq;
    setLoading(true);
    setError('');
    try {
      const data = await superworkApi.getScheduleEvents({
        from: visibleRange.start.format(DATE_KEY),
        to: visibleRange.end.format(DATE_KEY),
        sources: sourceFilter === 'ALL' ? ALL_SOURCES : [sourceFilter],
      });
      if (seq !== requestSeq.current) return;
      setEvents(data.events || []);
      setHints(data.hints || []);
    } catch (e) {
      if (seq !== requestSeq.current) return;
      setEvents([]);
      setHints([]);
      setError(e instanceof Error ? e.message : '日程加载失败');
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, [sourceFilter, visibleRange]);

  useEffect(() => {
    void load();
  }, [load]);

  // 切换月份后选中日期跟随：当月取今天，跨月取月初。
  useEffect(() => {
    const today = dayjs();
    setSelectedDate(
      today.isSame(month, 'month')
        ? today.format(DATE_KEY)
        : month.startOf('month').format(DATE_KEY),
    );
  }, [month]);

  const dayGroups = useMemo(() => groupEventsByDay(events), [events]);
  const eventsByDay = useMemo(
    () => new Map(dayGroups.map((group) => [group.key, group.events])),
    [dayGroups],
  );
  const selectedEvents = eventsByDay.get(selectedDate) || [];
  const selectedDay = dayjs(selectedDate, DATE_KEY);
  const isEmpty = !loading && !error && events.length === 0;
  const detailMeta = detail ? SOURCE_META[detail.source] : undefined;

  const openEvent = (event: ScheduleEvent) => {
    if (event.source === 'MEETING' && event.meetingId != null) {
      history.push(`/meetings/${event.meetingId}`);
      return;
    }
    setDetail(event);
  };

  return (
    <div className="sw-page sw-schedule">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            OPERATIONS / SCHEDULE
          </Typography.Text>
          <Typography.Title level={2}>日程</Typography.Title>
          <Typography.Paragraph type="secondary">
            合并本地会议与企微日程，支持日历视图与列表模式；周期日程按场次展开。
          </Typography.Paragraph>
        </div>
        <Space wrap>
          <Segmented
            value={mode}
            onChange={(value) => setMode(value as ViewMode)}
            options={[
              { label: '日历视图', value: 'calendar' },
              { label: '列表模式', value: 'list' },
            ]}
          />
          <Select
            value={sourceFilter}
            style={{ width: 150 }}
            options={SOURCE_OPTIONS}
            onChange={(value) => setSourceFilter(value as SourceFilter)}
          />
        </Space>
      </div>

      <div className="sw-schedule-toolbar">
        <Space size={8}>
          <Button
            icon={<LeftOutlined />}
            aria-label="上一月"
            onClick={() => setMonth((value) => value.subtract(1, 'month'))}
          />
          <Button onClick={() => setMonth(dayjs().startOf('month'))}>今天</Button>
          <Button
            icon={<RightOutlined />}
            aria-label="下一月"
            onClick={() => setMonth((value) => value.add(1, 'month'))}
          />
        </Space>
        <Typography.Title level={4} className="sw-schedule-month">
          {month.format('YYYY 年 M 月')}
        </Typography.Title>
        <Space size={12}>
          <Typography.Text type="secondary">
            共 {events.length} 条日程
          </Typography.Text>
          <Button
            icon={<ReloadOutlined />}
            loading={loading}
            onClick={() => void load()}
          >
            刷新
          </Button>
        </Space>
      </div>

      {hints.length > 0 && (
        <Alert
          type="warning"
          showIcon
          className="sw-schedule-alert"
          message="企微日程提示"
          description={
            <div className="sw-schedule-hints">
              {hints.map((hint) => (
                <div key={hint}>{renderHint(hint)}</div>
              ))}
            </div>
          }
        />
      )}

      {error && (
        <Alert
          type="error"
          showIcon
          className="sw-schedule-alert"
          message="无法读取日程数据"
          description={error}
          action={
            <Button size="small" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      )}

      {isEmpty && (
        <Alert
          type="info"
          showIcon
          className="sw-schedule-alert"
          message="本月暂无日程"
          description="可切换月份或调整来源筛选查看其它日程。"
        />
      )}

      <Spin spinning={loading}>
        {mode === 'calendar' ? (
          <div className="sw-schedule-body">
            <Card variant="borderless" className="sw-schedule-calendar">
              <div className="sw-schedule-weekdays">
                {WEEKDAY_LABELS.map((label) => (
                  <div key={label} className="sw-schedule-weekday">
                    周{label}
                  </div>
                ))}
              </div>
              <div className="sw-schedule-grid">
                {grid.flat().map((day) => {
                  const key = day.format(DATE_KEY);
                  const dayEvents = eventsByDay.get(key) || [];
                  const outside = !day.isSame(month, 'month');
                  const isToday = day.isSame(dayjs(), 'day');
                  const selected = key === selectedDate;
                  return (
                    <div
                      key={key}
                      role="button"
                      tabIndex={0}
                      className={[
                        'sw-schedule-cell',
                        outside ? 'sw-schedule-cell-outside' : '',
                        selected ? 'sw-schedule-cell-selected' : '',
                      ]
                        .filter(Boolean)
                        .join(' ')}
                      onClick={() => setSelectedDate(key)}
                      onKeyDown={(keyboardEvent) => {
                        if (keyboardEvent.key === 'Enter') setSelectedDate(key);
                      }}
                    >
                      <div className="sw-schedule-cell-head">
                        <span
                          className={`sw-schedule-daynum${
                            isToday ? ' sw-schedule-daynum-today' : ''
                          }`}
                        >
                          {day.date()}
                        </span>
                        {isToday && (
                          <span className="sw-schedule-today">今天</span>
                        )}
                        {dayEvents.length > 0 && (
                          <span className="sw-schedule-count">
                            {dayEvents.length}
                          </span>
                        )}
                      </div>
                      <div className="sw-schedule-chips">
                        {dayEvents.slice(0, MAX_CELL_CHIPS).map((event) => (
                          <button
                            type="button"
                            key={event.id}
                            className={`sw-schedule-chip sw-schedule-chip-${
                              SOURCE_META[event.source].tone
                            }`}
                            title={`${timeRangeText(event)} ${event.title}`}
                            onClick={(mouseEvent) => {
                              mouseEvent.stopPropagation();
                              openEvent(event);
                            }}
                          >
                            {event.recurring && <SyncOutlined />}
                            <span className="sw-schedule-chip-time">
                              {event.allDay
                                ? '全天'
                                : parseWallTime(event.start).format('HH:mm')}
                            </span>
                            <span className="sw-schedule-chip-title">
                              {event.title}
                            </span>
                          </button>
                        ))}
                        {dayEvents.length > MAX_CELL_CHIPS && (
                          <span className="sw-schedule-more">
                            +{dayEvents.length - MAX_CELL_CHIPS}
                          </span>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            </Card>
            <Card
              variant="borderless"
              className="sw-schedule-day-panel"
              title={`${selectedDay.format('M 月 D 日')} · ${
                WEEKDAY_NAMES[selectedDay.day()]
              }`}
              extra={
                <Typography.Text type="secondary">
                  {selectedEvents.length} 条
                </Typography.Text>
              }
            >
              {selectedEvents.length > 0 ? (
                <div className="sw-schedule-day-list">
                  {selectedEvents.map((event) => (
                    <ScheduleEventRow
                      key={event.id}
                      event={event}
                      onOpen={openEvent}
                    />
                  ))}
                </div>
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="当日暂无日程"
                />
              )}
            </Card>
          </div>
        ) : (
          <Card variant="borderless" className="sw-schedule-list-card">
            {dayGroups.length > 0 ? (
              dayGroups.map((group) => (
                <section className="sw-schedule-list-day" key={group.key}>
                  <div className="sw-schedule-list-date">
                    <Typography.Title level={5}>
                      {group.day.format('M 月 D 日')}
                    </Typography.Title>
                    <Typography.Text type="secondary">
                      {WEEKDAY_NAMES[group.day.day()]} · {group.events.length} 条
                    </Typography.Text>
                  </div>
                  <div className="sw-schedule-day-list">
                    {group.events.map((event) => (
                      <ScheduleEventRow
                        key={event.id}
                        event={event}
                        onOpen={openEvent}
                      />
                    ))}
                  </div>
                </section>
              ))
            ) : (
              <Empty description="本月暂无日程" />
            )}
          </Card>
        )}
      </Spin>

      <Drawer
        title={detail?.title || '日程详情'}
        size={560}
        open={Boolean(detail)}
        onClose={() => setDetail(undefined)}
      >
        {detail && (
          <>
            <Descriptions bordered column={1} size="small">
              <Descriptions.Item label="来源">
                <Tag color={detailMeta?.color}>{detailMeta?.label}</Tag>
                {detail.recurring && (
                  <Tag icon={<SyncOutlined />}>周期场次</Tag>
                )}
              </Descriptions.Item>
              <Descriptions.Item label="时间">
                {detail.allDay
                  ? `${parseWallTime(detail.start).format(
                      'YYYY-MM-DD',
                    )} 全天`
                  : `${parseWallTime(detail.start).format(
                      'YYYY-MM-DD HH:mm',
                    )} ~ ${parseWallTime(detail.end).format('HH:mm')}`}
              </Descriptions.Item>
              {detail.location && (
                <Descriptions.Item label="地点">
                  {detail.location}
                </Descriptions.Item>
              )}
              {detail.organizer && (
                <Descriptions.Item label="组织者">
                  {detail.organizer}
                </Descriptions.Item>
              )}
              {detail.calendarName && (
                <Descriptions.Item label="日历">
                  {detail.calendarName}
                </Descriptions.Item>
              )}
              <Descriptions.Item label="参与人">
                {detail.participants?.length ? (
                  <Space size={[4, 4]} wrap>
                    {detail.participants.map((name) => (
                      <Tag key={name}>{name}</Tag>
                    ))}
                  </Space>
                ) : (
                  '—'
                )}
              </Descriptions.Item>
              {detail.meetingCode && (
                <Descriptions.Item label="线上会议号">
                  {detail.meetingCode}
                </Descriptions.Item>
              )}
              {detail.meetingLink && (
                <Descriptions.Item label="入会链接">
                  <a
                    href={detail.meetingLink}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    {detail.meetingLink}
                  </a>
                </Descriptions.Item>
              )}
              {detail.status && (
                <Descriptions.Item label="状态">
                  {MEETING_STATUS_LABEL[detail.status] || detail.status}
                </Descriptions.Item>
              )}
              {detail.scheduleId && (
                <Descriptions.Item label="日程 ID">
                  {detail.scheduleId}
                </Descriptions.Item>
              )}
            </Descriptions>
            {detail.description && (
              <div className="sw-schedule-description">
                <Typography.Text type="secondary">描述</Typography.Text>
                <Typography.Paragraph>{detail.description}</Typography.Paragraph>
              </div>
            )}
          </>
        )}
      </Drawer>
    </div>
  );
}
