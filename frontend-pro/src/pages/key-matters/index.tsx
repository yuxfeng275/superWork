import {
  CalendarOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  FilterOutlined,
  FolderOpenOutlined,
  MonitorOutlined,
  MoreOutlined,
  PlusOutlined,
  ReloadOutlined,
  UserOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import { useModel } from "@umijs/max";
import {
  Alert,
  Avatar,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Drawer,
  Dropdown,
  Empty,
  Form,
  Input,
  InputNumber,
  List,
  Modal,
  message,
  Pagination,
  Popover,
  Progress,
  Row,
  Segmented,
  Select,
  Space,
  Tag,
  Typography,
} from "antd";
import dayjs from "dayjs";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { superworkApi } from "@/services/superwork/api";
import "../workbench/style.less";
import "./style.less";

type Matter = Record<string, any>;
type PresentationDraft = {
  status?: string;
  progress?: number;
  progressSummary?: string;
  issues?: string;
  nextWeekPlan?: string;
  supportNeeded?: string;
};
const statusOptions = [
  "未开始",
  "推进中",
  "有风险",
  "已阻塞",
  "已完成",
  "已暂停",
];
const mondayOf = (value?: dayjs.ConfigType) => {
  const date = dayjs(value);
  const weekday = date.day() || 7;
  return date.startOf("day").subtract(weekday - 1, "day");
};
// 周会导航分组头像配色：按分组键散列取色，避免 antd 默认灰色让人误以为禁用
const GROUP_AVATAR_TONES = [
  "linear-gradient(135deg, #1677ff 0%, #5b5ce2 100%)",
  "linear-gradient(135deg, #0f9b8e 0%, #16a3d6 100%)",
  "linear-gradient(135deg, #7c5cff 0%, #b04df0 100%)",
  "linear-gradient(135deg, #d97706 0%, #f0a92e 100%)",
  "linear-gradient(135deg, #1f8a4c 0%, #4fb069 100%)",
  "linear-gradient(135deg, #c2456b 0%, #e0728f 100%)",
];
// 状态配色：周会演示的状态标签、平铺选项与进度条共用同一套表达
const STATUS_TONES: Record<string, { hex: string; tag: string }> = {
  未开始: { hex: '#8c8c8c', tag: 'default' },
  推进中: { hex: '#1677ff', tag: 'processing' },
  有风险: { hex: '#fa8c16', tag: 'warning' },
  已阻塞: { hex: '#ff4d4f', tag: 'error' },
  已完成: { hex: '#52c41a', tag: 'success' },
  已暂停: { hex: '#722ed1', tag: 'purple' },
};
export default function KeyMattersPage() {
  const { initialState } = useModel("@@initialState");
  const [access, setAccess] = useState<Record<string, unknown>>({});
  const [rows, setRows] = useState<Matter[]>([]);
  const [meeting, setMeeting] = useState<Matter[]>([]);
  const [presentationGroupBy, setPresentationGroupBy] = useState<
    "owner" | "project"
  >("project");
  const [presentationOpen, setPresentationOpen] = useState(false);
  const [presentationIndex, setPresentationIndex] = useState(0);
  const [presentationEditing, setPresentationEditing] = useState(false);
  const [presentationSaving, setPresentationSaving] = useState(false);
  const [presentationDraft, setPresentationDraft] =
    useState<PresentationDraft>();
  const presentationDrafts = useRef(new Map<number, PresentationDraft>());
  const [filters, setFilters] = useState({
    keyword: "",
    status: "",
    priority: "",
    businessLineId: undefined as number | undefined,
    ownerId: undefined as number | undefined,
    projectId: undefined as number | undefined,
  });
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [quickFilterTab, setQuickFilterTab] = useState<"project" | "owner">(
    "project"
  );
  const [personalScope, setPersonalScope] = useState<
    "all" | "owned" | "participating"
  >("all");
  const [milestoneMonth, setMilestoneMonth] = useState(
    dayjs().format("YYYY-MM")
  );
  const [milestoneExpanded, setMilestoneExpanded] = useState(false);
  const milestoneScrollerRef = useRef<HTMLDivElement>(null);
  const [milestoneCanScrollLeft, setMilestoneCanScrollLeft] = useState(false);
  const [milestoneCanScrollRight, setMilestoneCanScrollRight] = useState(false);
  const updateMilestoneScrollState = useCallback(() => {
    const scroller = milestoneScrollerRef.current;
    if (!scroller) {
      setMilestoneCanScrollLeft(false);
      setMilestoneCanScrollRight(false);
      return;
    }
    const maxScrollLeft = Math.max(
      scroller.scrollWidth - scroller.clientWidth,
      0
    );
    setMilestoneCanScrollLeft(scroller.scrollLeft > 2);
    setMilestoneCanScrollRight(scroller.scrollLeft < maxScrollLeft - 2);
  }, []);
  const scrollMilestones = (direction: -1 | 1) => {
    const scroller = milestoneScrollerRef.current;
    if (!scroller) return;
    scroller.scrollBy({
      left: direction * Math.max(scroller.clientWidth * 0.72, 320),
      behavior: "smooth",
    });
    window.setTimeout(updateMilestoneScrollState, 260);
  };
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<Matter>();
  const [form] = Form.useForm();
  const [detail, setDetail] = useState<Matter>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [weeklyOpen, setWeeklyOpen] = useState(false);
  // 周进展弹窗独立持有事项，避免复用 detail 时连带打开详情抽屉
  const [weeklyMatter, setWeeklyMatter] = useState<Matter>();
  const [weeklyForm] = Form.useForm();
  const weeklyFormProgress = Form.useWatch("progress", weeklyForm);
  const weeklyFormStatus = Form.useWatch("status", weeklyForm);
  const [users, setUsers] = useState<
    Array<{ id: number; realName?: string; username?: string }>
  >([]);
  const [projects, setProjects] = useState<
    Array<{
      id: number;
      name: string;
      parentId?: number | null;
      businessLineId?: number | null;
    }>
  >([]);
  const [businessLines, setBusinessLines] = useState<
    Array<{ id: number; name: string }>
  >([]);
  const canManageAll = access.canManageAll === true;
  const canCreateOwn = access.canCreateOwn === true;
  const canFeedbackOwn = access.canFeedbackOwn === true;
  const canCreate = canManageAll || canCreateOwn;
  const canEdit = (matter: Matter) =>
    canManageAll ||
    (canCreateOwn &&
      canFeedbackOwn &&
      Number(matter.ownerId) === Number(initialState?.currentUser?.id));
  const isStandaloneMeeting = window.location.pathname.endsWith(
    "/key-matters-meeting"
  );
  const currentUserId = Number(initialState?.currentUser?.id);
  // 周进展反馈权限：管理员或本人（且具备 feedbackOwn 权限），与编辑权限分开判断
  const canFeedback = (matter: Matter) =>
    canManageAll ||
    (canFeedbackOwn &&
      currentUserId > 0 &&
      Number(matter.ownerId) === currentUserId);
  const progressPresets = [0, 25, 50, 75, 90, 100];
  // 交付窗口：与旧版 milestoneTiming 对齐
  const milestoneTiming = (matter: Matter) => {
    if (matter.status === "已完成")
      return { label: "已完成", tone: "complete" };
    if (!matter.plannedCompletionDate)
      return { label: "未设置", tone: "upcoming" };
    const today = dayjs().startOf("day");
    const days = dayjs(matter.plannedCompletionDate)
      .startOf("day")
      .diff(today, "day");
    if (days < 0)
      return { label: `逾期 ${Math.abs(days)} 天`, tone: "overdue" };
    if (days === 0) return { label: "今日到期", tone: "today" };
    return { label: `${days} 天后`, tone: "upcoming" };
  };
  const milestoneGroupTone = (items: Matter[]) => {
    const hasRisk = items.some((matter) => {
      const timing = milestoneTiming(matter);
      return (
        timing.tone === "overdue" ||
        ["有风险", "已阻塞"].includes(matter.status)
      );
    });
    if (hasRisk) return "risk";
    if (items.every((matter) => matter.status === "已完成")) return "complete";
    if (items.some((matter) => milestoneTiming(matter).tone === "today"))
      return "today";
    return "upcoming";
  };
  const milestoneGroupSymbol = (items: Matter[]) => {
    const tone = milestoneGroupTone(items);
    if (tone === "complete") return "✓";
    if (tone === "today") return "◷";
    if (tone === "risk") return "!";
    return "?";
  };
  const progressComparison = (
    current: number,
    previous: number | undefined,
    missingLabel = "暂无对比数据"
  ) => {
    if (previous === undefined) return { label: missingLabel, tone: "muted" };
    const delta = current - previous;
    if (delta > 0) return { label: `较上周 +${delta}%`, tone: "up" };
    if (delta < 0) return { label: `较上周 ${delta}%`, tone: "down" };
    return { label: "较上周持平", tone: "flat" };
  };
  // 历史周进展对比：与后一条（更早一周）比较，最早一条为基线
  const historyDelta = (updates: Matter[], index: number) => {
    const update = updates[index];
    const previous = updates[index + 1];
    if (!update || !previous) return { label: "基线", tone: "muted" };
    const delta = Number(update.progress || 0) - Number(previous.progress || 0);
    if (delta > 0) return { label: `+${delta}%`, tone: "up" };
    if (delta < 0) return { label: `${delta}%`, tone: "down" };
    return { label: "持平", tone: "flat" };
  };
  const detailDelta = (matter: Matter) => {
    const updates: Matter[] = matter.weeklyUpdates || [];
    const latest = matter.latestUpdate || updates[0];
    if (!latest) return { label: "尚无周进展", tone: "missing" };
    const previous = updates.find(
      (item) => item.weekStartDate < latest.weekStartDate
    );
    return progressComparison(
      Number(latest.progress || 0),
      previous ? Number(previous.progress || 0) : undefined
    );
  };
  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const a = await superworkApi.getKeyMatterAccess();
      setAccess(a);
      if (a.canAccess === false) return;
      const [list, userPage, projectPage, businessLinePage] = await Promise.all(
        [
          superworkApi.getKeyMatters(),
          superworkApi.getUsers({ page: 1, size: 300 }),
          superworkApi.getProjects({ page: 1, size: 300 }),
          superworkApi.getBusinessLines({ page: 1, size: 200 }),
        ]
      );
      setRows(list);
      setUsers(userPage.records || []);
      setProjects(projectPage.records || []);
      setBusinessLines(businessLinePage.records || []);
      try {
        setMeeting(
          await superworkApi.getKeyMatterMeeting(
            mondayOf().format("YYYY-MM-DD")
          )
        );
      } catch {
        setMeeting([]);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "大事儿加载失败");
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);
  useEffect(() => {
    if (!isStandaloneMeeting) return undefined;
    document.body.classList.add("key-matters-standalone");
    return () => document.body.classList.remove("key-matters-standalone");
  }, [isStandaloneMeeting]);
  const openDetail = async (matter: Matter) => {
    setDetail(matter);
    setDetailLoading(true);
    try {
      setDetail(await superworkApi.getKeyMatter(matter.id));
    } catch (e) {
      message.error(e instanceof Error ? e.message : "详情加载失败");
    } finally {
      setDetailLoading(false);
    }
  };
  const openWeekly = (matter: Matter, week = mondayOf()) => {
    if (!canFeedback(matter)) {
      message.warning("仅事项负责人可反馈周进度");
      return;
    }
    if (matter.status === "已完成" && !matter.currentWeekUpdate) {
      message.info("本周已完成，无需更新");
      return;
    }
    const weekText = week.format("YYYY-MM-DD");
    const update =
      (matter.weeklyUpdates || []).find(
        (item: Matter) => item.weekStartDate === weekText
      ) ||
      (matter.currentWeekUpdate?.weekStartDate === weekText
        ? matter.currentWeekUpdate
        : undefined);
    setWeeklyMatter(matter);
    // 拉取完整详情，保证弹窗右侧历史周进展有数据（列表行不含完整周报）
    void superworkApi
      .getKeyMatter(matter.id)
      .then((full) =>
        setWeeklyMatter((prev) =>
          prev?.id === matter.id ? (full as Matter) : prev
        )
      )
      .catch(() => undefined);
    weeklyForm.resetFields();
    weeklyForm.setFieldsValue({
      weekStartDate: week,
      status: update?.status || matter.status || "推进中",
      progress: update?.progress ?? matter.progress ?? 0,
      progressSummary: update?.progressSummary || "",
      issues: update?.issues || "",
      nextWeekPlan: update?.nextWeekPlan || "",
      supportNeeded: update?.supportNeeded || "",
    });
    setWeeklyOpen(true);
  };
  const openEdit = (matter: Matter) => {
    if (!canEdit(matter)) {
      message.warning("仅事项负责人或管理员可编辑大事儿");
      return;
    }
    setEditing(matter);
    form.resetFields();
    form.setFieldsValue({
      title: matter.title,
      description: matter.description || "",
      ownerId: matter.ownerId,
      projectId: matter.projectId,
      participantIds: Array.from(
        new Set(
          (matter.participants || [])
            .map((item: Matter) => item.userId)
            .concat(matter.ownerId)
            .filter(Boolean)
        )
      ),
      priority: matter.priority || "P1",
      status: matter.status || "未开始",
      progress: matter.progress ?? 0,
      startDate: matter.startDate ? dayjs(matter.startDate) : undefined,
      plannedCompletionDate: matter.plannedCompletionDate
        ? dayjs(matter.plannedCompletionDate)
        : undefined,
      sortOrder: matter.sortOrder ?? 0,
    });
    setOpen(true);
  };
  const startPresentationEdit = () => {
    if (!presentationMatter) return;
    if (!canFeedback(presentationMatter)) {
      message.warning("仅事项负责人可反馈周进度");
      return;
    }
    setPresentationEditing(true);
  };
  const savePresentationAndNext = async () => {
    if (!presentationMatter || !presentationDraft) return;
    const summary = String(presentationDraft.progressSummary || "").trim();
    if (!summary) {
      message.warning("请至少填写一项本周进展");
      return;
    }
    setPresentationSaving(true);
    try {
      await superworkApi.upsertKeyMatterWeeklyUpdate(
        presentationMatter.id,
        mondayOf().format("YYYY-MM-DD"),
        {
          status: presentationDraft.status || presentationMatter.status,
          progress:
            presentationDraft.status === "已完成"
              ? 100
              : presentationDraft.progress,
          progressSummary: summary,
          issues: String(presentationDraft.issues || "").trim() || undefined,
          nextWeekPlan:
            String(presentationDraft.nextWeekPlan || "").trim() || undefined,
          supportNeeded:
            String(presentationDraft.supportNeeded || "").trim() || undefined,
        }
      );
      message.success(
        presentationIndex + 1 >= presentationItems.length
          ? "周报已保存"
          : "周报已保存，已切换到下一项"
      );
      presentationDrafts.current.delete(presentationMatter.id);
      await load();
      const nextIndex = Math.min(
        presentationIndex + 1,
        presentationItems.length - 1
      );
      setPresentationIndex(nextIndex);
      setPresentationEditing(false);
    } catch (e) {
      message.error(e instanceof Error ? e.message : "周报保存失败");
    } finally {
      setPresentationSaving(false);
    }
  };
  const saveWeekly = async (values: Record<string, unknown>) => {
    if (!weeklyMatter) return;
    try {
      const week = mondayOf(values.weekStartDate as dayjs.ConfigType).format(
        "YYYY-MM-DD"
      );
      await superworkApi.upsertKeyMatterWeeklyUpdate(weeklyMatter.id, week, {
        status: values.status,
        progress: values.status === "已完成" ? 100 : values.progress,
        progressSummary: String(values.progressSummary || "").trim(),
        issues: values.issues,
        nextWeekPlan: values.nextWeekPlan,
        supportNeeded: values.supportNeeded,
      });
      message.success("周进展已保存");
      setWeeklyOpen(false);
      // 详情抽屉本就打开时刷新其数据；未打开则不主动弹出（周进展只更新周报）
      if (detail) await openDetail(detail);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : "周进展保存失败");
    }
  };
  const save = async () => {
    const v = await form.validateFields();
    try {
      const ownerId = canManageAll
        ? v.ownerId
        : Number(initialState?.currentUser?.id) || v.ownerId;
      const payload = {
        ...v,
        ownerId,
        startDate: v.startDate
          ? dayjs(v.startDate).format("YYYY-MM-DD")
          : undefined,
        plannedCompletionDate: v.plannedCompletionDate
          ? dayjs(v.plannedCompletionDate).format("YYYY-MM-DD")
          : undefined,
        participantIds: Array.from(
          new Set(
            [
              ...(Array.isArray(v.participantIds) ? v.participantIds : []),
              ownerId,
            ].filter(Boolean)
          )
        ),
      };
      if (editing) await superworkApi.updateKeyMatter(editing.id, payload);
      else await superworkApi.createKeyMatter(payload);
      message.success("事项已保存");
      setOpen(false);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : "保存失败");
    }
  };
  const remove = async (r: Matter) => {
    Modal.confirm({
      title: "删除大事儿",
      content: `确定删除「${r.title}」吗？其全部周进展也会删除。`,
      okText: "删除",
      cancelText: "取消",
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await superworkApi.deleteKeyMatter(r.id);
          message.success("事项已删除");
          await load();
        } catch (e) {
          message.error(e instanceof Error ? e.message : "删除失败");
        }
      },
    });
  };
  const projectPresentation = (matter: Matter) => {
    const projectId = matter.projectId ? Number(matter.projectId) : undefined;
    const project = projects.find((item) => item.id === projectId);
    let rootId = matter.projectRootId
      ? Number(matter.projectRootId)
      : projectId;
    const visited = new Set<number>();
    while (rootId && !visited.has(rootId)) {
      visited.add(rootId);
      const current = projects.find((item) => item.id === rootId);
      if (!current?.parentId) break;
      rootId = Number(current.parentId);
    }
    const root = projects.find((item) => item.id === rootId);
    return {
      rootId,
      rootName: root?.name || matter.projectName || "BU 内部事项",
      displayName:
        root && project && root.id !== project.id
          ? `${root.name}-${project.name}`
          : matter.projectName || root?.name || "BU 内部事项",
    };
  };
  const currentUpdate = (matter: Matter) => matter.currentWeekUpdate;
  const effectiveStatus = (matter: Matter) =>
    currentUpdate(matter)?.status || matter.status || "未开始";
  const effectiveProgress = (matter: Matter) =>
    Number(currentUpdate(matter)?.progress ?? matter.progress ?? 0);
  const presentationGroups = useMemo(() => {
    const groups = new Map<string, Matter[]>();
    meeting.forEach((item) => {
      const key =
        presentationGroupBy === "owner"
          ? String(item.ownerId || "unassigned")
          : String(projectPresentation(item).rootId || "internal");
      groups.set(key, [...(groups.get(key) || []), item]);
    });
    return Array.from(groups.entries()).map(([key, items]) => {
      const updateRequired = items.filter(
        (item) => effectiveStatus(item) !== "已完成"
      );
      return {
        key,
        label:
          presentationGroupBy === "owner"
            ? items[0]?.ownerName || "未指定负责人"
            : projectPresentation(items[0]).rootName,
        items,
        updatedCount: updateRequired.filter((item) => item.currentWeekUpdate)
          .length,
        updateRequiredCount: updateRequired.length,
        riskCount: items.filter((item) =>
          ["有风险", "已阻塞"].includes(effectiveStatus(item))
        ).length,
        averageProgress: Math.round(
          items.reduce((sum, item) => sum + effectiveProgress(item), 0) /
            items.length
        ),
      };
    });
  }, [meeting, presentationGroupBy, projects]);
  const presentationItems = useMemo(
    () => presentationGroups.flatMap((group) => group.items),
    [presentationGroups]
  );
  const presentationMatter = presentationItems[presentationIndex];
  // 演示卡片头部平铺展示：状态与进度跟随草稿（编辑态）或本周周报
  const presentationStatus = presentationMatter
    ? (presentationEditing ? presentationDraft?.status : undefined) ||
      effectiveStatus(presentationMatter)
    : "";
  const presentationProgress = presentationEditing
    ? presentationDraft?.progress ?? 0
    : presentationMatter
    ? effectiveProgress(presentationMatter)
    : 0;
  const presentationStatusTone =
    STATUS_TONES[presentationStatus]?.tag ?? "default";
  const presentationStatusHex =
    STATUS_TONES[presentationStatus]?.hex ?? "#8c8c8c";
  const presentationAvatarTone = (key: string) => {
    let hash = 0;
    for (const char of key) hash = (hash * 31 + char.charCodeAt(0)) % 9973;
    return GROUP_AVATAR_TONES[hash % GROUP_AVATAR_TONES.length];
  };
  const draftForMatter = (matter?: Matter): PresentationDraft | undefined => {
    if (!matter) return undefined;
    return (
      presentationDrafts.current.get(matter.id) || {
        status: matter.currentWeekUpdate?.status || matter.status,
        progress: matter.currentWeekUpdate?.progress ?? matter.progress ?? 0,
        progressSummary: matter.currentWeekUpdate?.progressSummary || "",
        issues: matter.currentWeekUpdate?.issues || "",
        nextWeekPlan: matter.currentWeekUpdate?.nextWeekPlan || "",
        supportNeeded: matter.currentWeekUpdate?.supportNeeded || "",
      }
    );
  };
  const cachePresentationDraft = () => {
    if (presentationEditing && presentationMatter && presentationDraft) {
      presentationDrafts.current.set(presentationMatter.id, {
        ...presentationDraft,
      });
    }
  };
  const stashPresentationDraft = () => {
    cachePresentationDraft();
    message.success("草稿已暂存，本次周会期间可继续编辑");
  };
  const matterStats = useMemo(() => {
    const total = rows.length;
    const progressing = rows.filter((r) => r.status === "推进中").length;
    const risks = rows.filter((r) =>
      ["有风险", "已阻塞"].includes(r.status)
    ).length;
    const updateRequired = rows.filter((r) => r.status !== "已完成");
    return {
      total,
      progressing,
      risks,
      completed: rows.filter((r) => r.status === "已完成").length,
      pending: updateRequired.filter(
        (r) => !r.currentWeekUpdate && r.currentWeekUpdated !== true
      ).length,
      updateRequiredCount: updateRequired.length,
      updatedCount: updateRequired.filter(
        (r) => r.currentWeekUpdate || r.currentWeekUpdated === true
      ).length,
      progressingRate: total ? Math.round((progressing / total) * 100) : 0,
      riskRate: total ? Math.round((risks / total) * 100) : 0,
    };
  }, [rows]);
  const filteredRows = useMemo(() => {
    const keyword = filters.keyword.trim().toLowerCase();
    const currentUserId = Number(initialState?.currentUser?.id);
    return rows.filter((item) => {
      const matchesKeyword =
        !keyword ||
        [item.title, item.description, item.projectName]
          .filter(Boolean)
          .some((value) => String(value).toLowerCase().includes(keyword));
      const matchesOwner =
        filters.ownerId === undefined ||
        (filters.ownerId === 0
          ? !item.ownerId
          : Number(item.ownerId) === filters.ownerId);
      const matchesProject =
        filters.projectId === undefined ||
        (filters.projectId === 0
          ? !item.projectId
          : projectPresentation(item).rootId === filters.projectId);
      const matchesBusinessLine =
        filters.businessLineId === undefined ||
        (() => {
          const projectId = item.projectId ? Number(item.projectId) : undefined;
          const rootId = projectPresentation(item).rootId;
          const businessLineOf = (id?: number) =>
            id === undefined
              ? undefined
              : projects.find((project) => project.id === id)?.businessLineId;
          return (
            businessLineOf(projectId) === filters.businessLineId ||
            businessLineOf(rootId) === filters.businessLineId
          );
        })();
      const matchesStatus = !filters.status || item.status === filters.status;
      const matchesPriority =
        !filters.priority || item.priority === filters.priority;
      const isOwned =
        currentUserId > 0 && Number(item.ownerId) === currentUserId;
      const isParticipating =
        currentUserId > 0 &&
        !isOwned &&
        Array.isArray(item.participants) &&
        item.participants.some(
          (participant: Matter) => Number(participant.userId) === currentUserId
        );
      const matchesPersonal =
        personalScope === "all" ||
        (personalScope === "owned" ? isOwned : isParticipating);
      return (
        matchesKeyword &&
        matchesOwner &&
        matchesProject &&
        matchesBusinessLine &&
        matchesStatus &&
        matchesPriority &&
        matchesPersonal
      );
    });
  }, [filters, initialState?.currentUser?.id, personalScope, projects, rows]);
  useEffect(() => {
    setCurrentPage(1);
  }, [
    filters.keyword,
    filters.status,
    filters.priority,
    filters.businessLineId,
    filters.ownerId,
    filters.projectId,
    personalScope,
  ]);
  const pagedRows = useMemo(
    () =>
      filteredRows.slice((currentPage - 1) * pageSize, currentPage * pageSize),
    [currentPage, filteredRows, pageSize]
  );
  const projectGroups = useMemo(() => {
    const groups = new Map<
      string,
      { id?: number; label: string; count: number }
    >();
    rows.forEach((item) => {
      let id = item.projectId ? Number(item.projectId) : undefined;
      const visited = new Set<number>();
      while (id && !visited.has(id)) {
        visited.add(id);
        const project = projects.find((candidate) => candidate.id === id);
        if (!project?.parentId) break;
        id = project.parentId;
      }
      const key = id === undefined ? "none" : String(id);
      const current = groups.get(key) || {
        id: id ?? 0,
        label: id
          ? projects.find((project) => project.id === id)?.name ||
            item.projectName ||
            "未关联项目"
          : "未关联项目",
        count: 0,
      };
      current.count += 1;
      groups.set(key, current);
    });
    return Array.from(groups.values()).sort(
      (a, b) => b.count - a.count || a.label.localeCompare(b.label, "zh-CN")
    );
  }, [projects, rows]);
  const ownerGroups = useMemo(() => {
    const groups = new Map<
      string,
      { id?: number; label: string; count: number }
    >();
    rows.forEach((item) => {
      const id = item.ownerId ? Number(item.ownerId) : undefined;
      const key = id === undefined ? "none" : String(id);
      const current = groups.get(key) || {
        id: id ?? 0,
        label: item.ownerName || "未指定负责人",
        count: 0,
      };
      current.count += 1;
      groups.set(key, current);
    });
    return Array.from(groups.values()).sort(
      (a, b) => b.count - a.count || a.label.localeCompare(b.label, "zh-CN")
    );
  }, [rows]);
  const ownedCount = useMemo(
    () =>
      rows.filter(
        (item) => currentUserId > 0 && Number(item.ownerId) === currentUserId
      ).length,
    [rows, currentUserId]
  );
  const participatingCount = useMemo(
    () =>
      rows.filter(
        (item) =>
          currentUserId > 0 &&
          Number(item.ownerId) !== currentUserId &&
          Array.isArray(item.participants) &&
          item.participants.some(
            (participant: Matter) =>
              Number(participant.userId) === currentUserId
          )
      ).length,
    [rows, currentUserId]
  );
  const milestoneItems = useMemo(() => {
    const groups = new Map<string, Matter[]>();
    rows
      .filter((item) =>
        String(item.plannedCompletionDate || "").startsWith(milestoneMonth)
      )
      .sort((a, b) =>
        String(a.plannedCompletionDate).localeCompare(
          String(b.plannedCompletionDate)
        )
      )
      .forEach((item) => {
        groups.set(item.plannedCompletionDate, [
          ...(groups.get(item.plannedCompletionDate) || []),
          item,
        ]);
      });
    return Array.from(groups.entries()).map(([date, items]) => ({
      date,
      items,
    }));
  }, [milestoneMonth, rows]);
  useEffect(() => {
    if (!milestoneExpanded) return;
    const scroller = milestoneScrollerRef.current;
    if (scroller) scroller.scrollLeft = 0;
    updateMilestoneScrollState();
  }, [milestoneExpanded, milestoneItems, updateMilestoneScrollState]);
  const renderMatterListItem = (r: Matter) => {
    const status = r.status || "未开始";
    const progress = Number(r.progress || 0);
    return (
      <List.Item className="sw-matter-list-item">
        <div className="sw-matter-list-row">
          <div className="sw-matter-list-primary">
            <div className="sw-matter-list-title-row">
              <Tag
                color={
                  r.priority === "P0"
                    ? "red"
                    : r.priority === "P1"
                    ? "gold"
                    : "blue"
                }
              >
                {r.priority || "P2"}
              </Tag>
              <Typography.Link
                className="sw-matter-list-title"
                onClick={() => void openDetail(r)}
              >
                {r.title}
              </Typography.Link>
              <Tag
                color={
                  status === "已完成"
                    ? "success"
                    : ["有风险", "已阻塞"].includes(status)
                    ? "error"
                    : "processing"
                }
              >
                {status}
              </Tag>
            </div>
            <Typography.Text
              type="secondary"
              className="sw-matter-list-project"
            >
              <FolderOpenOutlined /> {r.projectName || "BU 内部事项"}
            </Typography.Text>
            <Typography.Paragraph
              ellipsis={{ rows: 1 }}
              type="secondary"
              className="sw-matter-list-description"
            >
              {r.description || "暂无事项说明"}
            </Typography.Paragraph>
          </div>
          <div className="sw-matter-list-progress">
            <div className="sw-progress-line">
              <Typography.Text type="secondary">当前进度</Typography.Text>
              <Progress
                percent={progress}
                size={{ height: 8 }}
                showInfo={false}
                status={
                  status === "已阻塞"
                    ? "exception"
                    : status === "已完成"
                    ? "success"
                    : "active"
                }
              />
              <Typography.Text strong>{progress}%</Typography.Text>
            </div>
            {status === "推进中" && (
              <div
                className={`sw-progress-line is-weekly ${
                  r.currentWeekUpdate ? "" : "is-idle"
                }`}
              >
                <Typography.Text type="secondary">本周进展</Typography.Text>
                <Typography.Text strong>
                  {r.currentWeekUpdate
                    ? `${Number(r.currentWeekUpdate.progress ?? 0)}%`
                    : "待更新"}
                </Typography.Text>
              </div>
            )}
          </div>
          <div className="sw-matter-list-meta">
            <Typography.Text type="secondary">
              <UserOutlined />{" "}
              {r.ownerName || r.owner?.realName || "未指定负责人"}
            </Typography.Text>
            <Typography.Text
              type={
                r.overdue || milestoneTiming(r).tone === "overdue"
                  ? "danger"
                  : "secondary"
              }
            >
              计划 {r.plannedCompletionDate || "—"}
            </Typography.Text>
            <Tag
              color={
                r.currentWeekUpdate
                  ? "success"
                  : status === "已完成"
                  ? "default"
                  : "warning"
              }
            >
              {r.currentWeekUpdate
                ? "本周已更新"
                : status === "已完成"
                ? "无需更新"
                : canEdit(r)
                ? "本周待更新"
                : "待负责人反馈"}
            </Tag>
          </div>
          <Space className="sw-matter-list-actions" size={0}>
            {canFeedback(r) && status !== "已完成" && (
              <Button
                type="link"
                icon={<CalendarOutlined />}
                onClick={() => openWeekly(r)}
              >
                周进展
              </Button>
            )}
            <Button
              type="link"
              icon={<EyeOutlined />}
              onClick={() => void openDetail(r)}
            >
              详情
            </Button>
            <Dropdown
              trigger={["click"]}
              placement="bottomRight"
              menu={{
                items: [
                  {
                    key: "edit",
                    icon: <EditOutlined />,
                    label: "编辑",
                    disabled: !canEdit(r),
                  },
                  {
                    key: "delete",
                    icon: <DeleteOutlined />,
                    label: "删除",
                    danger: true,
                    disabled: !canManageAll,
                  },
                ],
                onClick: ({ key }) => {
                  if (key === "edit") openEdit(r);
                  if (key === "delete") void remove(r);
                },
              }}
            >
              <Button type="link" icon={<MoreOutlined />}>
                更多
              </Button>
            </Dropdown>
          </Space>
        </div>
      </List.Item>
    );
  };
  useEffect(() => {
    if (!presentationOpen) return undefined;
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (target?.matches('input, textarea, [contenteditable="true"]')) return;
      if (event.key === "ArrowLeft")
        navigatePresentation(presentationIndex - 1);
      if (event.key === "ArrowRight")
        navigatePresentation(presentationIndex + 1);
      if (event.key === "Escape") setPresentationOpen(false);
      if (event.key === "f" || event.key === "F") {
        if (document.fullscreenElement) {
          const exit = document.exitFullscreen?.();
          if (exit) void exit.catch(() => undefined);
        } else {
          const request = document.documentElement.requestFullscreen?.();
          if (request) void request.catch(() => undefined);
        }
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [presentationItems.length, presentationIndex, presentationOpen]);
  // 进入演示时自动进入编辑态：本周尚无进展或已有暂存草稿（对齐旧版 hydratePresentationForm）
  const shouldAutoEditPresentation = (matter?: Matter) =>
    Boolean(
      matter &&
        canFeedback(matter) &&
        matter.status !== "已完成" &&
        (!matter.currentWeekUpdate || presentationDrafts.current.has(matter.id))
    );
  const openPresentation = (index = 0, requestFullscreen = true) => {
    if (!meeting.length) {
      message.info("本周暂无可演示事项");
      return;
    }
    const targetId = meeting[index]?.id;
    const groupedIndex =
      targetId === undefined
        ? index
        : presentationItems.findIndex((item) => item.id === targetId);
    const nextIndex = groupedIndex >= 0 ? groupedIndex : 0;
    setPresentationIndex(nextIndex);
    const matter = presentationItems[nextIndex];
    setPresentationDraft(draftForMatter(matter));
    setPresentationEditing(shouldAutoEditPresentation(matter));
    setPresentationOpen(true);
    if (requestFullscreen) {
      const request = document.documentElement.requestFullscreen?.();
      if (request) void request.catch(() => undefined);
    }
  };
  const exitPresentation = () => {
    setPresentationOpen(false);
    const exit = document.exitFullscreen?.();
    if (exit) void exit.catch(() => undefined);
  };
  // 切换分组方式时保持当前事项定位（对齐旧版 setPresentationGroupBy）
  const changePresentationGroupBy = (value: "project" | "owner") => {
    const currentId = presentationMatter?.id;
    cachePresentationDraft();
    setPresentationGroupBy(value);
    const groups = new Map<string, Matter[]>();
    meeting.forEach((item) => {
      const key =
        value === "owner"
          ? String(item.ownerId || "unassigned")
          : String(projectPresentation(item).rootId || "internal");
      groups.set(key, [...(groups.get(key) || []), item]);
    });
    const items = Array.from(groups.values()).flat();
    const nextIndex = Math.max(
      items.findIndex((item) => item.id === currentId),
      0
    );
    const matter = items[nextIndex];
    setPresentationIndex(nextIndex);
    setPresentationDraft(draftForMatter(matter));
    setPresentationEditing(shouldAutoEditPresentation(matter));
  };
  const currentPresentationGroupKey = presentationGroups.find((group) =>
    group.items.some((item) => item.id === presentationMatter?.id)
  )?.key;
  const navigatePresentation = (index: number) => {
    if (!presentationItems.length) return;
    cachePresentationDraft();
    const nextIndex =
      (index + presentationItems.length) % presentationItems.length;
    const matter = presentationItems[nextIndex];
    setPresentationIndex(nextIndex);
    setPresentationDraft(draftForMatter(matter));
    setPresentationEditing(shouldAutoEditPresentation(matter));
  };
  useEffect(() => {
    if (!isStandaloneMeeting || presentationOpen || loading) return;
    if (meeting.length) openPresentation(0, false);
    else setPresentationOpen(true);
  }, [isStandaloneMeeting, loading, meeting.length, presentationOpen]);
  if (access.canAccess === false)
    return (
      <div className="sw-page">
        <Alert
          type="warning"
          showIcon
          message="暂无大事儿管理权限"
          description="权限由后端 /api/key-matters/access 动态决定。"
        />
      </div>
    );
  return (
    <div
      className={`sw-page sw-key-matters ${
        isStandaloneMeeting ? "is-standalone-meeting" : ""
      } ${presentationOpen ? "is-presenting" : ""}`}
    >
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            COLLABORATION / KEY MATTERS
          </Typography.Text>
          <Typography.Title level={2}>大事儿管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            登记、推进和复盘 BU
            级重点事项；会议视图与事项登记共用同一真实数据源。
          </Typography.Paragraph>
        </div>
        <Row gutter={12} className="sw-matter-stats">
          <Col xs={24} sm={12} lg={6}>
            <Card variant="borderless">
              <Space>
                <Avatar
                  className="sw-stat-icon"
                  icon={<FolderOpenOutlined />}
                />
                <div>
                  <Typography.Text type="secondary">全部事项</Typography.Text>
                  <Typography.Title level={3}>
                    {matterStats.total}
                  </Typography.Title>
                </div>
              </Space>
            </Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card variant="borderless">
              <Space>
                <Avatar
                  className="sw-stat-icon progressing"
                  icon={<UserOutlined />}
                />
                <div>
                  <Typography.Text type="secondary">推进中</Typography.Text>
                  <Typography.Title level={3}>
                    {matterStats.progressing}
                  </Typography.Title>
                  <Typography.Text type="secondary" className="sw-stat-note">
                    占比 {matterStats.progressingRate}%
                  </Typography.Text>
                </div>
              </Space>
            </Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card variant="borderless">
              <Space>
                <Avatar
                  className="sw-stat-icon risk"
                  icon={<WarningOutlined />}
                />
                <div>
                  <Typography.Text type="secondary">
                    风险 / 阻塞
                  </Typography.Text>
                  <Typography.Title level={3}>
                    {matterStats.risks}
                  </Typography.Title>
                  <Typography.Text type="secondary" className="sw-stat-note">
                    占比 {matterStats.riskRate}%
                  </Typography.Text>
                </div>
              </Space>
            </Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card variant="borderless">
              <Space>
                <Avatar
                  className="sw-stat-icon done"
                  icon={<CalendarOutlined />}
                />
                <div>
                  <Typography.Text type="secondary">待更新</Typography.Text>
                  <Typography.Title level={3}>
                    {matterStats.pending}
                  </Typography.Title>
                  <Typography.Text type="secondary" className="sw-stat-note">
                    已更新 {matterStats.updatedCount}/
                    {matterStats.updateRequiredCount}
                  </Typography.Text>
                </div>
              </Space>
            </Card>
          </Col>
        </Row>
        <Space>
          <Button
            icon={<MonitorOutlined />}
            title="进入周会全屏"
            onClick={() =>
              window.open(
                "/key-matters-meeting",
                "_blank",
                "noopener,noreferrer"
              )
            }
          >
            周会全屏
          </Button>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            disabled={!canCreate}
            onClick={() => {
              setEditing(undefined);
              form.resetFields();
              form.setFieldsValue({
                status: "未开始",
                progress: 0,
                priority: "P1",
                ownerId: canManageAll
                  ? undefined
                  : Number(initialState?.currentUser?.id) || undefined,
                participantIds: canManageAll
                  ? []
                  : [Number(initialState?.currentUser?.id)].filter(Boolean),
                startDate: dayjs(),
              });
              setOpen(true);
            }}
          >
            新建事项
          </Button>
        </Space>
      </div>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取大事儿"
          description={error}
        />
      )}
      {!presentationOpen && (
        <>
          <Card variant="borderless" className="sw-milestone-card">
            <div className="sw-milestone-strip">
              <Space align="center">
                <Typography.Text strong>
                  <CalendarOutlined /> 交付节点
                </Typography.Text>
                <Input
                  type="month"
                  value={milestoneMonth}
                  onChange={(event) =>
                    setMilestoneMonth(
                      event.target.value || dayjs().format("YYYY-MM")
                    )
                  }
                />
                <Button
                  type="link"
                  onClick={() => setMilestoneExpanded((value) => !value)}
                >
                  {milestoneExpanded
                    ? "收起"
                    : `展开（${milestoneItems.length} 个节点）`}
                </Button>
              </Space>
              {milestoneExpanded && (
                <div className="sw-milestone-panel">
                  <button
                    type="button"
                    className="sw-milestone-slide"
                    disabled={!milestoneCanScrollLeft}
                    aria-label="向左查看里程碑"
                    onClick={() => scrollMilestones(-1)}
                  >
                    ‹
                  </button>
                  <div
                    ref={milestoneScrollerRef}
                    className="sw-milestone-track sw-milestone-timeline"
                    onScroll={updateMilestoneScrollState}
                  >
                    {milestoneItems.length ? (
                      milestoneItems.map((item) => {
                        const tone = milestoneGroupTone(item.items);
                        return (
                          <Popover
                            key={item.date}
                            trigger="hover"
                            placement="bottom"
                            content={
                              <Space
                                orientation="vertical"
                                size={4}
                                style={{ minWidth: 240 }}
                              >
                                <Typography.Text strong>
                                  {dayjs(item.date).format("YYYY年MM月DD日")} ·{" "}
                                  {item.items.length} 个事项
                                </Typography.Text>
                                {item.items.map((matter) => (
                                  <Button
                                    key={matter.id}
                                    type="link"
                                    style={{
                                      padding: 0,
                                      textAlign: "left",
                                      height: "auto",
                                    }}
                                    onClick={() => void openDetail(matter)}
                                  >
                                    {matter.title} ·{" "}
                                    {matter.ownerName || "未指定负责人"} ·{" "}
                                    {matter.status || "未开始"} ·{" "}
                                    {matter.progress ?? 0}%
                                  </Button>
                                ))}
                              </Space>
                            }
                          >
                            <button
                              type="button"
                              className={`node-${tone}`}
                              aria-label={`${item.date}，${item.items.length}个事项`}
                              onClick={() => {
                                if (item.items.length === 1)
                                  void openDetail(item.items[0]);
                              }}
                            >
                              <span className={`sw-milestone-dot ${tone}`}>
                                {milestoneGroupSymbol(item.items)}
                              </span>
                              <strong>
                                {dayjs(item.date).format("MM/DD")}
                              </strong>
                              <small>
                                {item.items.length} 项 · {item.items[0].status}
                              </small>
                            </button>
                          </Popover>
                        );
                      })
                    ) : (
                      <Typography.Text type="secondary">
                        本月暂无计划完成事项
                      </Typography.Text>
                    )}
                  </div>
                  <button
                    type="button"
                    className="sw-milestone-slide"
                    disabled={!milestoneCanScrollRight}
                    aria-label="向右查看里程碑"
                    onClick={() => scrollMilestones(1)}
                  >
                    ›
                  </button>
                </div>
              )}
            </div>
          </Card>
          <div className="sw-register-layout">
            <aside className="sw-register-sidebar">
              <Card variant="borderless" className="sw-quick-filter-card">
                <Space wrap size={[8, 8]}>
                  <Typography.Text strong>
                    <FilterOutlined /> 快速筛选
                  </Typography.Text>
                  <Button
                    type={
                      personalScope === "all" &&
                      !filters.ownerId &&
                      !filters.projectId
                        ? "primary"
                        : "default"
                    }
                    onClick={() => {
                      setPersonalScope("all");
                      setFilters((current) => ({
                        ...current,
                        ownerId: undefined,
                        projectId: undefined,
                      }));
                    }}
                  >
                    全部事项 {rows.length}
                  </Button>
                  <Button
                    type={personalScope === "owned" ? "primary" : "default"}
                    onClick={() => {
                      setPersonalScope("owned");
                      setFilters((current) => ({
                        ...current,
                        ownerId: undefined,
                        projectId: undefined,
                      }));
                    }}
                  >
                    我的事项 {ownedCount}
                  </Button>
                  <Button
                    type={
                      personalScope === "participating" ? "primary" : "default"
                    }
                    onClick={() => {
                      setPersonalScope("participating");
                      setFilters((current) => ({
                        ...current,
                        ownerId: undefined,
                        projectId: undefined,
                      }));
                    }}
                  >
                    我参与的 {participatingCount}
                  </Button>
                  <Typography.Text type="secondary">按</Typography.Text>
                  <Segmented
                    value={quickFilterTab}
                    onChange={(value) =>
                      setQuickFilterTab(value as "project" | "owner")
                    }
                    options={[
                      { label: "项目", value: "project" },
                      { label: "负责人", value: "owner" },
                    ]}
                  />
                  {(quickFilterTab === "project"
                    ? projectGroups
                    : ownerGroups
                  ).map((group) => (
                    <Button
                      key={`${quickFilterTab}-${group.id ?? "none"}`}
                      type={
                        (
                          quickFilterTab === "project"
                            ? filters.projectId === group.id
                            : filters.ownerId === group.id
                        )
                          ? "primary"
                          : "default"
                      }
                      onClick={() => {
                        setPersonalScope("all");
                        setFilters((current) => ({
                          ...current,
                          ownerId:
                            quickFilterTab === "owner" ? group.id : undefined,
                          projectId:
                            quickFilterTab === "project" ? group.id : undefined,
                        }));
                      }}
                    >
                      {group.label} · {group.count}
                    </Button>
                  ))}
                </Space>
              </Card>
            </aside>
            <section className="sw-register-results">
              <Card variant="borderless" className="sw-matter-tools">
                <Space wrap>
                  <Typography.Text strong>
                    <FilterOutlined /> 查询
                  </Typography.Text>
                  <Input
                    allowClear
                    value={filters.keyword}
                    placeholder="搜索事项标题、说明或项目"
                    style={{ width: 240 }}
                    onChange={(event) =>
                      setFilters((current) => ({
                        ...current,
                        keyword: event.target.value,
                      }))
                    }
                  />
                  <Select
                    allowClear
                    placeholder="状态"
                    style={{ width: 130 }}
                    value={filters.status || undefined}
                    options={statusOptions.map((status) => ({
                      label: status,
                      value: status,
                    }))}
                    onChange={(value) =>
                      setFilters((current) => ({
                        ...current,
                        status: value || "",
                      }))
                    }
                  />
                  <Select
                    allowClear
                    placeholder="优先级"
                    style={{ width: 110 }}
                    value={filters.priority || undefined}
                    options={["P0", "P1", "P2"].map((priority) => ({
                      label: priority,
                      value: priority,
                    }))}
                    onChange={(value) =>
                      setFilters((current) => ({
                        ...current,
                        priority: value || "",
                      }))
                    }
                  />
                  <Select
                    allowClear
                    placeholder="业务线"
                    style={{ width: 150 }}
                    value={filters.businessLineId}
                    options={businessLines.map((line) => ({
                      value: line.id,
                      label: line.name,
                    }))}
                    onChange={(value) =>
                      setFilters((current) => ({
                        ...current,
                        businessLineId: value,
                      }))
                    }
                  />
                  <Button
                    type="link"
                    onClick={() => {
                      setFilters({
                        keyword: "",
                        status: "",
                        priority: "",
                        businessLineId: undefined,
                        ownerId: undefined,
                        projectId: undefined,
                      });
                      setPersonalScope("all");
                    }}
                  >
                    重置
                  </Button>
                </Space>
              </Card>
              <Card
                variant="borderless"
                loading={loading}
                className="sw-matter-list-card"
              >
                {filteredRows.length ? (
                  <List
                    className="sw-matter-list"
                    dataSource={pagedRows}
                    rowKey={(item) => String(item.id)}
                    renderItem={(item) => renderMatterListItem(item)}
                  />
                ) : (
                  <Empty
                    description={
                      rows.length ? "没有符合条件的事项" : "暂无大事儿"
                    }
                  />
                )}
              </Card>
              <div className="sw-matter-pagination">
                <Typography.Text type="secondary">
                  共 {filteredRows.length} 项
                </Typography.Text>
                <Pagination
                  current={currentPage}
                  pageSize={pageSize}
                  total={filteredRows.length}
                  showSizeChanger
                  pageSizeOptions={[10, 20, 50]}
                  onChange={(page, size) => {
                    setCurrentPage(page);
                    setPageSize(size);
                  }}
                  showTotal={(total) => `共 ${total} 项`}
                />
              </div>
            </section>
          </div>
        </>
      )}
      <Modal
        title={editing ? "编辑大事儿" : "新建大事儿"}
        open={open}
        onCancel={() => setOpen(false)}
        onOk={() => void save()}
        okText="保存"
        cancelText="取消"
      >
        <Form
          form={form}
          layout="vertical"
          onValuesChange={(changed) => {
            if (changed.status === "已完成")
              form.setFieldValue("progress", 100);
            if (changed.progress !== undefined) {
              const progress = Number(changed.progress || 0);
              if (progress >= 100) form.setFieldValue("status", "已完成");
              else if (form.getFieldValue("status") === "已完成")
                form.setFieldValue("status", "推进中");
            }
          }}
        >
          <Form.Item name="title" label="事项标题" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="事项说明">
            <Input.TextArea rows={3} />
          </Form.Item>
          <Space style={{ display: "flex" }}>
            <Form.Item
              name="ownerId"
              label="负责人"
              rules={[{ required: true }]}
            >
              <Select
                showSearch
                optionFilterProp="label"
                style={{ width: 180 }}
                disabled={!canManageAll}
                options={users.map((u) => ({
                  value: u.id,
                  label: u.realName || u.username || String(u.id),
                }))}
              />
            </Form.Item>
            <Form.Item
              name="projectId"
              label="关联项目"
              rules={[{ required: true, message: "请选择项目" }]}
            >
              <Select
                showSearch
                optionFilterProp="label"
                placeholder="请选择项目"
                style={{ width: 180 }}
                options={projects.map((p) => ({ value: p.id, label: p.name }))}
              />
            </Form.Item>
          </Space>
          {canManageAll && (
            <Form.Item name="participantIds" label="参与人">
              <Select
                mode="multiple"
                showSearch
                optionFilterProp="label"
                options={users.map((u) => ({
                  value: u.id,
                  label: u.realName || u.username || String(u.id),
                }))}
              />
            </Form.Item>
          )}
          <Space wrap>
            <Form.Item name="status" label="状态">
              <Select
                style={{ width: 180 }}
                options={statusOptions.map((v) => ({ label: v, value: v }))}
              />
            </Form.Item>
            <Form.Item name="priority" label="优先级">
              <Select
                style={{ width: 120 }}
                options={["P0", "P1", "P2"].map((v) => ({
                  label: v,
                  value: v,
                }))}
              />
            </Form.Item>
            <Form.Item name="sortOrder" label="排序">
              <InputNumber min={0} max={999} />
            </Form.Item>
          </Space>
          <Form.Item name="progress" label="进度">
            <InputNumber min={0} max={100} suffix="%" />
          </Form.Item>
          <Form.Item label="快速选择进度">
            <Space size={4} wrap>
              {progressPresets.map((preset) => (
                <Button
                  key={preset}
                  size="small"
                  onClick={() => {
                    form.setFieldValue("progress", preset);
                    if (preset === 100) form.setFieldValue("status", "已完成");
                    else if (form.getFieldValue("status") === "已完成")
                      form.setFieldValue("status", "推进中");
                  }}
                >
                  {preset}%
                </Button>
              ))}
            </Space>
          </Form.Item>
          <Space style={{ display: "flex" }}>
            <Form.Item name="startDate" label="开始日期">
              <DatePicker format="YYYY-MM-DD" />
            </Form.Item>
            <Form.Item name="plannedCompletionDate" label="计划完成日期">
              <DatePicker format="YYYY-MM-DD" />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
      <Drawer
        title={detail?.title || "大事儿详情"}
        size={680}
        open={Boolean(detail)}
        onClose={() => setDetail(undefined)}
      >
        {detailLoading ? (
          <Typography.Text>加载中…</Typography.Text>
        ) : (
          detail && (
            <>
              <Descriptions bordered column={2} size="small">
                <Descriptions.Item label="负责人">
                  {detail.ownerName || detail.ownerId || "—"}
                </Descriptions.Item>
                <Descriptions.Item label="状态">
                  <Space size={4}>
                    <Tag>{detail.status || "—"}</Tag>
                    {(detail.overdue ||
                      milestoneTiming(detail).tone === "overdue") && (
                      <Tag color="red">已逾期</Tag>
                    )}
                  </Space>
                </Descriptions.Item>
                <Descriptions.Item label="优先级">
                  {detail.priority || "—"}
                </Descriptions.Item>
                <Descriptions.Item label="进度">
                  <Space size={6}>
                    <span>{detail.progress ?? 0}%</span>
                    <Typography.Text type="secondary">
                      {detailDelta(detail).label}
                    </Typography.Text>
                  </Space>
                </Descriptions.Item>
                <Descriptions.Item label="项目" span={2}>
                  {detail.projectName || "—"}
                </Descriptions.Item>
                <Descriptions.Item label="开始日期">
                  {detail.startDate || "—"}
                </Descriptions.Item>
                <Descriptions.Item label="计划完成">
                  {detail.plannedCompletionDate || "—"}
                </Descriptions.Item>
                <Descriptions.Item label="交付窗口">
                  {milestoneTiming(detail).label}
                </Descriptions.Item>
                <Descriptions.Item label="事项说明" span={2}>
                  {detail.description || "—"}
                </Descriptions.Item>
                <Descriptions.Item label="参与人" span={2}>
                  {detail.participants
                    ?.map((p: Matter) => p.realName || p.username || p.userId)
                    .join("、") || "—"}
                </Descriptions.Item>
              </Descriptions>
              <Space style={{ marginTop: 18 }}>
                <Button
                  icon={<EditOutlined />}
                  disabled={!canEdit(detail)}
                  onClick={() => openEdit(detail)}
                >
                  编辑事项
                </Button>
                <Button
                  type="primary"
                  disabled={
                    !canFeedback(detail) ||
                    (detail.status === "已完成" && !detail.currentWeekUpdate)
                  }
                  onClick={() => openWeekly(detail)}
                >
                  填写本周进展
                </Button>
              </Space>
              <Card size="small" title="最新周进展" style={{ marginTop: 16 }}>
                {detail.latestUpdate || detail.currentWeekUpdate ? (
                  <Space
                    orientation="vertical"
                    style={{ width: "100%" }}
                    size={6}
                  >
                    <Space wrap>
                      <Tag color="blue">
                        {(detail.latestUpdate || detail.currentWeekUpdate)
                          .status || detail.status}
                      </Tag>
                      <Typography.Text strong>
                        {(detail.latestUpdate || detail.currentWeekUpdate)
                          .progress ??
                          detail.progress ??
                          0}
                        %
                      </Typography.Text>
                      <Typography.Text type="secondary">
                        {
                          (detail.latestUpdate || detail.currentWeekUpdate)
                            .weekStartDate
                        }
                      </Typography.Text>
                    </Space>
                    <Typography.Paragraph style={{ margin: 0 }}>
                      {(detail.latestUpdate || detail.currentWeekUpdate)
                        .progressSummary || "暂无进展说明"}
                    </Typography.Paragraph>
                    {(detail.latestUpdate || detail.currentWeekUpdate)
                      .issues && (
                      <Typography.Text type="danger">
                        问题 / 风险：
                        {
                          (detail.latestUpdate || detail.currentWeekUpdate)
                            .issues
                        }
                      </Typography.Text>
                    )}
                    {(detail.latestUpdate || detail.currentWeekUpdate)
                      .nextWeekPlan && (
                      <Typography.Text>
                        下一步：
                        {
                          (detail.latestUpdate || detail.currentWeekUpdate)
                            .nextWeekPlan
                        }
                      </Typography.Text>
                    )}
                    {(detail.latestUpdate || detail.currentWeekUpdate)
                      .supportNeeded && (
                      <Typography.Text type="warning">
                        需协调：
                        {
                          (detail.latestUpdate || detail.currentWeekUpdate)
                            .supportNeeded
                        }
                      </Typography.Text>
                    )}
                  </Space>
                ) : (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description="尚未填写周进展"
                  />
                )}
              </Card>
              <Typography.Title level={5} style={{ marginTop: 20 }}>
                周进展
              </Typography.Title>
              <List
                size="small"
                dataSource={detail.weeklyUpdates || []}
                locale={{
                  emptyText: (
                    <Empty
                      image={Empty.PRESENTED_IMAGE_SIMPLE}
                      description="暂无周进展"
                    />
                  ),
                }}
                renderItem={(item: Matter, index: number) => {
                  const delta = historyDelta(detail.weeklyUpdates || [], index);
                  return (
                    <List.Item>
                      <List.Item.Meta
                        title={
                          <Space size={6} wrap>
                            <span>
                              {item.weekStartDate || "未知周"} ·{" "}
                              {item.status || "未设置"} · {item.progress ?? 0}%
                            </span>
                            <Tag
                              color={
                                delta.tone === "up"
                                  ? "success"
                                  : delta.tone === "down"
                                  ? "error"
                                  : "default"
                              }
                            >
                              {delta.label}
                            </Tag>
                            {index === 0 && <Tag color="blue">最新</Tag>}
                          </Space>
                        }
                        description={
                          <Space orientation="vertical" size={2}>
                            <Typography.Text>
                              {item.progressSummary || "暂无进展说明"}
                            </Typography.Text>
                            {item.issues && (
                              <Typography.Text type="danger">
                                问题 / 风险：{item.issues}
                              </Typography.Text>
                            )}
                            {item.nextWeekPlan && (
                              <Typography.Text type="secondary">
                                下一步：{item.nextWeekPlan}
                              </Typography.Text>
                            )}
                            {item.supportNeeded && (
                              <Typography.Text type="warning">
                                需协调：{item.supportNeeded}
                              </Typography.Text>
                            )}
                            {item.updatedAt && (
                              <Typography.Text type="secondary">
                                更新于{" "}
                                {dayjs(item.updatedAt).format(
                                  "YYYY-MM-DD HH:mm"
                                )}
                              </Typography.Text>
                            )}
                          </Space>
                        }
                      />
                      {canFeedback(detail) && item.weekStartDate && (
                        <Space size={0}>
                          <Button
                            type="link"
                            onClick={() =>
                              void openWeekly(detail, dayjs(item.weekStartDate))
                            }
                          >
                            编辑
                          </Button>
                          <Button
                            type="link"
                            danger
                            onClick={async () => {
                              try {
                                await superworkApi.deleteKeyMatterWeeklyUpdate(
                                  detail.id,
                                  item.weekStartDate
                                );
                                message.success("周进展已删除");
                                await openDetail(detail);
                                await load();
                              } catch (e) {
                                message.error(
                                  e instanceof Error
                                    ? e.message
                                    : "周进展删除失败"
                                );
                              }
                            }}
                          >
                            删除
                          </Button>
                        </Space>
                      )}
                    </List.Item>
                  );
                }}
              />
            </>
          )
        )}
      </Drawer>
      {presentationOpen && (
        <div className="sw-presentation-layout">
          <aside className="sw-presentation-nav" aria-label="演示分组导航">
            <div className="sw-presentation-nav-head">
              <Typography.Text className="sw-eyebrow">快速导航</Typography.Text>
              <Segmented
                block
                value={presentationGroupBy}
                options={[
                  { value: "project", label: "项目" },
                  { value: "owner", label: "负责人" },
                ]}
                onChange={(value) =>
                  changePresentationGroupBy(value as "project" | "owner")
                }
              />
            </div>
            {presentationGroups.map((group) => {
              const isActiveGroup = group.key === currentPresentationGroupKey;
              return (
                <div
                  key={group.key}
                  className={`sw-presentation-group ${
                    isActiveGroup ? "active" : ""
                  }`}
                >
                  <button
                    type="button"
                    className="sw-presentation-group-main"
                    onClick={() => {
                      const firstIndex = presentationItems.findIndex(
                        (item) => item.id === group.items[0]?.id
                      );
                      if (!isActiveGroup && firstIndex >= 0)
                        navigatePresentation(firstIndex);
                    }}
                  >
                    <Avatar
                      size={28}
                      shape={
                        presentationGroupBy === "owner" ? "circle" : "square"
                      }
                      className="sw-presentation-group-avatar"
                      style={{
                        backgroundImage: presentationAvatarTone(
                          String(group.key)
                        ),
                      }}
                    >
                      {group.label.slice(0, 1)}
                    </Avatar>
                    <span className="sw-presentation-group-copy">
                      <strong>{group.label}</strong>
                      <small>
                        {group.items.length} 项事项 · {group.averageProgress}
                        %完成
                      </small>
                    </span>
                  </button>
                  <div className="sw-presentation-group-stats">
                    {group.updateRequiredCount > 0 ? (
                      <span>
                        {group.updatedCount}/{group.updateRequiredCount} 已更新
                      </span>
                    ) : (
                      <span>无需更新</span>
                    )}
                    {group.riskCount > 0 && (
                      <span className="has-risk">{group.riskCount} 风险</span>
                    )}
                  </div>
                  <div className="sw-presentation-group-matters">
                    {group.items.map((matter) => {
                      const matterIndex = presentationItems.findIndex(
                        (item) => item.id === matter.id
                      );
                      const needsUpdate =
                        effectiveStatus(matter) !== "已完成" &&
                        !matter.currentWeekUpdate;
                      return (
                        <button
                          key={matter.id}
                          type="button"
                          className={`${
                            matter.id === presentationMatter?.id ? "active" : ""
                          } ${
                            needsUpdate
                              ? canFeedback(matter)
                                ? "pending"
                                : "waiting"
                              : ""
                          }`}
                          onClick={() => navigatePresentation(matterIndex)}
                        >
                          <Typography.Text ellipsis>
                            {matter.title}
                          </Typography.Text>
                        </button>
                      );
                    })}
                  </div>
                </div>
              );
            })}
          </aside>
          <div className="sw-presentation-main">
            <section
              className="sw-presentation-stage"
              aria-label="周会演示模式"
            >
              {presentationMatter ? (
                <>
                  <Space
                    style={{ width: "100%", justifyContent: "space-between" }}
                  >
                    <Tag color="blue">
                      {presentationIndex + 1} / {presentationItems.length}
                    </Tag>
                    <Space>
                      <Button
                        onClick={() =>
                          navigatePresentation(presentationIndex - 1)
                        }
                      >
                        上一项
                      </Button>
                      <Button
                        onClick={() =>
                          navigatePresentation(presentationIndex + 1)
                        }
                      >
                        下一项
                      </Button>
                      {!isStandaloneMeeting && (
                        <Button onClick={exitPresentation}>退出演示</Button>
                      )}
                    </Space>
                  </Space>
                  <div className="sw-presentation-title-row">
                    <Typography.Title level={1}>
                      {presentationMatter.title}
                    </Typography.Title>
                    <Typography.Text
                      type="secondary"
                      className="sw-presentation-owner"
                    >
                      <FolderOpenOutlined />{" "}
                      {presentationMatter.projectName || "BU 内部事项"}
                      <span className="sw-presentation-owner-divider" />
                      <UserOutlined />{" "}
                      {presentationMatter.ownerName || "未指定负责人"}
                    </Typography.Text>
                  </div>
                  <Row gutter={[12, 12]} className="sw-presentation-cards">
                    <Col xs={24}>
                      <Card size="small" className="sw-presentation-brief">
                        <header className="sw-presentation-brief-head">
                          <strong>本周进展</strong>
                          {presentationEditing ? (
                            <Segmented
                              className="sw-presentation-brief-status"
                              value={presentationDraft?.status}
                              options={statusOptions.map((value) => ({
                                value,
                                label: (
                                  <span className="sw-presentation-status-option">
                                    <i
                                      style={{
                                        background: STATUS_TONES[value]?.hex,
                                      }}
                                    />
                                    {value}
                                  </span>
                                ),
                              }))}
                              onChange={(value) =>
                                setPresentationDraft((draft) => ({
                                  ...draft,
                                  status: String(value),
                                  progress:
                                    value === "已完成" ? 100 : draft?.progress,
                                }))
                              }
                            />
                          ) : (
                            <Tag color={presentationStatusTone}>
                              {presentationStatus || "未设置"}
                            </Tag>
                          )}
                          <div className="sw-presentation-brief-progress">
                            <Progress
                              percent={presentationProgress}
                              size={{ height: 8 }}
                              showInfo={false}
                              strokeColor={presentationStatusHex}
                              status={
                                presentationStatus === "已阻塞"
                                  ? "exception"
                                  : presentationStatus === "已完成"
                                  ? "success"
                                  : "active"
                              }
                            />
                            {presentationEditing ? (
                              <InputNumber
                                className="sw-presentation-brief-input"
                                min={0}
                                max={100}
                                suffix="%"
                                value={presentationDraft?.progress}
                                onChange={(value) =>
                                  setPresentationDraft((draft) => {
                                    const progress = Math.max(
                                      0,
                                      Math.min(
                                        100,
                                        Math.round(Number(value) || 0)
                                      )
                                    );
                                    return {
                                      ...draft,
                                      progress,
                                      status:
                                        progress === 100
                                          ? "已完成"
                                          : draft?.status === "已完成"
                                          ? "推进中"
                                          : draft?.status,
                                    };
                                  })
                                }
                              />
                            ) : (
                              <Typography.Text
                                strong
                                className="sw-presentation-brief-percent"
                              >
                                {presentationProgress}%
                              </Typography.Text>
                            )}
                          </div>
                          {presentationEditing && (
                            <Space
                              size={4}
                              wrap
                              className="sw-presentation-brief-presets"
                            >
                              {progressPresets.map((preset) => (
                                <Button
                                  key={preset}
                                  size="small"
                                  type={
                                    presentationDraft?.progress === preset
                                      ? "primary"
                                      : "default"
                                  }
                                  onClick={() =>
                                    setPresentationDraft((draft) => ({
                                      ...draft,
                                      progress: preset,
                                      status:
                                        preset === 100
                                          ? "已完成"
                                          : draft?.status === "已完成"
                                          ? "推进中"
                                          : draft?.status,
                                    }))
                                  }
                                >
                                  {preset}%
                                </Button>
                              ))}
                            </Space>
                          )}
                        </header>
                        {presentationEditing ? (
                          <Input.TextArea
                            rows={3}
                            value={presentationDraft?.progressSummary}
                            onChange={(event) =>
                              setPresentationDraft((draft) => ({
                                ...draft,
                                progressSummary: event.target.value,
                              }))
                            }
                            placeholder="逐条说明本周完成了什么、形成了什么结果"
                          />
                        ) : (
                          presentationMatter.currentWeekUpdate
                            ?.progressSummary || "尚未填写"
                        )}
                      </Card>
                    </Col>
                    <Col xs={24} md={12}>
                      <Card size="small" title="问题 / 风险">
                        {presentationEditing ? (
                          <Input.TextArea
                            rows={2}
                            value={presentationDraft?.issues}
                            onChange={(event) =>
                              setPresentationDraft((draft) => ({
                                ...draft,
                                issues: event.target.value,
                              }))
                            }
                            placeholder="没有可留空"
                          />
                        ) : (
                          presentationMatter.currentWeekUpdate?.issues ||
                          "本周暂无风险"
                        )}
                      </Card>
                    </Col>
                    <Col xs={24} md={12}>
                      <Card size="small" title="需协调 / 决策">
                        {presentationEditing ? (
                          <Input.TextArea
                            rows={2}
                            value={presentationDraft?.supportNeeded}
                            onChange={(event) =>
                              setPresentationDraft((draft) => ({
                                ...draft,
                                supportNeeded: event.target.value,
                              }))
                            }
                            placeholder="明确需要谁推动什么"
                          />
                        ) : (
                          presentationMatter.currentWeekUpdate?.supportNeeded ||
                          "暂无待协调事项"
                        )}
                      </Card>
                    </Col>
                    <Col xs={24}>
                      <Card size="small" title="下一步行动">
                        {presentationEditing ? (
                          <Input.TextArea
                            rows={2}
                            value={presentationDraft?.nextWeekPlan}
                            onChange={(event) =>
                              setPresentationDraft((draft) => ({
                                ...draft,
                                nextWeekPlan: event.target.value,
                              }))
                            }
                            placeholder="说明下一周期的关键动作"
                          />
                        ) : (
                          presentationMatter.currentWeekUpdate?.nextWeekPlan ||
                          "待补充"
                        )}
                      </Card>
                    </Col>
                  </Row>
                  <Space className="sw-presentation-actions" wrap>
                    {presentationEditing ? (
                      <>
                        <Button onClick={stashPresentationDraft}>
                          暂存草稿
                        </Button>
                        <Button
                          loading={presentationSaving}
                          type="primary"
                          onClick={() => void savePresentationAndNext()}
                        >
                          保存并下一项
                        </Button>
                        <Button onClick={() => setPresentationEditing(false)}>
                          取消
                        </Button>
                      </>
                    ) : (
                      <>
                        <Button
                          onClick={() => void openDetail(presentationMatter)}
                        >
                          查看详情
                        </Button>
                        {canFeedback(presentationMatter) && (
                          <Button
                            type="primary"
                            onClick={startPresentationEdit}
                          >
                            更新周报
                          </Button>
                        )}
                      </>
                    )}
                  </Space>
                </>
              ) : (
                <Empty description="本周暂无可演示事项" />
              )}
            </section>
            {presentationMatter && (
              <nav
                className="sw-presentation-thumbs"
                aria-label="演示事项快速导航"
              >
                {presentationItems.map((matter, index) => {
                  const requiresUpdate = effectiveStatus(matter) !== "已完成";
                  const complete =
                    !requiresUpdate || Boolean(matter.currentWeekUpdate);
                  const stateClass =
                    index === presentationIndex
                      ? "active"
                      : complete
                      ? "complete"
                      : canFeedback(matter)
                      ? "pending"
                      : "waiting";
                  return (
                    <button
                      key={matter.id}
                      type="button"
                      className={stateClass}
                      aria-label={`跳转到第 ${index + 1} 项`}
                      onClick={() => navigatePresentation(index)}
                    >
                      {index + 1}
                    </button>
                  );
                })}
              </nav>
            )}
          </div>
          {presentationMatter && (
            <aside
              className="sw-presentation-history"
              aria-label="大事儿历史周报"
            >
              <div className="sw-presentation-history-head">
                <Typography.Text strong>周进展记录</Typography.Text>
                <Typography.Text type="secondary">
                  {presentationMatter.weeklyUpdates?.length || 0} 次更新
                </Typography.Text>
              </div>
              {presentationMatter.weeklyUpdates?.length ? (
                <List
                  size="small"
                  dataSource={presentationMatter.weeklyUpdates}
                  renderItem={(item: Matter, index: number) => {
                    const delta = historyDelta(
                      presentationMatter.weeklyUpdates || [],
                      index
                    );
                    return (
                      <List.Item>
                        <Space wrap>
                          <Typography.Text strong>
                            {item.weekStartDate}
                          </Typography.Text>
                          <Tag>{item.status || "未设置"}</Tag>
                          <Typography.Text>
                            {item.progress ?? 0}%
                          </Typography.Text>
                          <Tag
                            color={
                              delta.tone === "up"
                                ? "success"
                                : delta.tone === "down"
                                ? "error"
                                : "default"
                            }
                          >
                            {delta.label}
                          </Tag>
                          {index === 0 && <Tag color="blue">最新</Tag>}
                          <Typography.Text type="secondary">
                            {item.progressSummary || "暂无进展说明"}
                          </Typography.Text>
                        </Space>
                      </List.Item>
                    );
                  }}
                />
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="暂无周进展记录"
                />
              )}
            </aside>
          )}
        </div>
      )}
      <Modal
        className="sw-weekly-modal"
        title={weeklyMatter?.title || "填写周进展"}
        open={weeklyOpen}
        onCancel={() => setWeeklyOpen(false)}
        onOk={() => void weeklyForm.submit()}
        okText="保存周进展"
        cancelText="取消"
        width={1080}
        style={{ top: 64 }}
      >
        {weeklyMatter && (
          <div className="sw-weekly-layout">
            <div className="sw-weekly-main sw-presentation-stage">
              <div className="sw-presentation-title-row">
                <Typography.Title level={1}>
                  {weeklyMatter.title}
                </Typography.Title>
                <Typography.Text
                  type="secondary"
                  className="sw-presentation-owner"
                >
                  <FolderOpenOutlined />{" "}
                  {weeklyMatter.projectName || "BU 内部事项"}
                  <span className="sw-presentation-owner-divider" />
                  <UserOutlined /> {weeklyMatter.ownerName || "未指定负责人"}
                </Typography.Text>
              </div>
              <Form
                form={weeklyForm}
                layout="vertical"
                className="sw-weekly-form"
                onValuesChange={(changed) => {
                  if (changed.status === "已完成")
                    weeklyForm.setFieldValue("progress", 100);
                  if (changed.progress !== undefined) {
                    const progress = Number(changed.progress || 0);
                    if (progress >= 100)
                      weeklyForm.setFieldValue("status", "已完成");
                    else if (weeklyForm.getFieldValue("status") === "已完成")
                      weeklyForm.setFieldValue("status", "推进中");
                  }
                }}
                onFinish={(values) => void saveWeekly(values)}
              >
                <Form.Item
                  name="weekStartDate"
                  hidden
                  rules={[{ required: true, message: "请选择周起始日" }]}
                >
                  <DatePicker />
                </Form.Item>
                <Row gutter={[12, 12]} className="sw-presentation-cards">
                  <Col xs={24}>
                    <Card size="small" className="sw-presentation-brief">
                      <header className="sw-presentation-brief-head">
                        <strong>本周进展</strong>
                        <Form.Item name="status" noStyle>
                          <Segmented
                            className="sw-presentation-brief-status"
                            options={statusOptions.map((value) => ({
                              value,
                              label: value,
                            }))}
                          />
                        </Form.Item>
                        <div className="sw-presentation-brief-progress">
                          <Progress
                            percent={Number(weeklyFormProgress || 0)}
                            size={{ height: 8 }}
                            showInfo={false}
                            status={
                              weeklyFormStatus === "已阻塞"
                                ? "exception"
                                : weeklyFormStatus === "已完成"
                                ? "success"
                                : "active"
                            }
                          />
                          <Form.Item name="progress" noStyle>
                            <InputNumber
                              className="sw-presentation-brief-input"
                              min={0}
                              max={100}
                              suffix="%"
                            />
                          </Form.Item>
                        </div>
                        <Space
                          size={4}
                          wrap
                          className="sw-presentation-brief-presets"
                        >
                          {progressPresets.map((preset) => (
                            <Button
                              key={preset}
                              size="small"
                              type={
                                Number(weeklyFormProgress) === preset
                                  ? "primary"
                                  : "default"
                              }
                              onClick={() => {
                                weeklyForm.setFieldValue("progress", preset);
                                if (preset === 100)
                                  weeklyForm.setFieldValue("status", "已完成");
                                else if (
                                  weeklyForm.getFieldValue("status") ===
                                  "已完成"
                                )
                                  weeklyForm.setFieldValue("status", "推进中");
                              }}
                            >
                              {preset}%
                            </Button>
                          ))}
                        </Space>
                      </header>
                      <Form.Item
                        name="progressSummary"
                        rules={[{ required: true, message: "请输入本周进展" }]}
                      >
                        <Input.TextArea
                          rows={3}
                          placeholder="逐条说明本周完成了什么、形成了什么结果"
                        />
                      </Form.Item>
                    </Card>
                  </Col>
                  <Col xs={24} md={12}>
                    <Card size="small" title="问题 / 风险">
                      <Form.Item name="issues">
                        <Input.TextArea rows={2} placeholder="没有可留空" />
                      </Form.Item>
                    </Card>
                  </Col>
                  <Col xs={24} md={12}>
                    <Card size="small" title="需协调 / 决策">
                      <Form.Item name="supportNeeded">
                        <Input.TextArea
                          rows={2}
                          placeholder="明确需要谁推动什么"
                        />
                      </Form.Item>
                    </Card>
                  </Col>
                  <Col xs={24}>
                    <Card size="small" title="下一步行动">
                      <Form.Item name="nextWeekPlan">
                        <Input.TextArea
                          rows={2}
                          placeholder="说明下一周期的关键动作"
                        />
                      </Form.Item>
                    </Card>
                  </Col>
                </Row>
              </Form>
            </div>
            <aside className="sw-weekly-history" aria-label="历史周进展">
              <div className="sw-weekly-history-head">
                <div>
                  <span className="sw-weekly-kicker">历史记录</span>
                  <strong>周进展记录</strong>
                </div>
                <Typography.Text type="secondary">
                  {weeklyMatter.weeklyUpdates?.length || 0} 次更新
                </Typography.Text>
              </div>
              {weeklyMatter.weeklyUpdates?.length ? (
                <List
                  size="small"
                  className="sw-weekly-history-list"
                  dataSource={weeklyMatter.weeklyUpdates}
                  renderItem={(item: Matter, index: number) => {
                    const delta = historyDelta(
                      weeklyMatter.weeklyUpdates || [],
                      index
                    );
                    return (
                      <List.Item
                        actions={
                          canFeedback(weeklyMatter) && item.weekStartDate
                            ? [
                                <Button
                                  key="edit"
                                  type="link"
                                  size="small"
                                  onClick={() =>
                                    openWeekly(
                                      weeklyMatter,
                                      dayjs(item.weekStartDate)
                                    )
                                  }
                                >
                                  编辑
                                </Button>,
                              ]
                            : undefined
                        }
                      >
                        <div className="sw-weekly-history-item">
                          <Space size={6} wrap>
                            <Typography.Text strong>
                              {item.weekStartDate}
                            </Typography.Text>
                            <Tag>{item.status || "未设置"}</Tag>
                            <Typography.Text>
                              {item.progress ?? 0}%
                            </Typography.Text>
                            <Tag
                              color={
                                delta.tone === "up"
                                  ? "success"
                                  : delta.tone === "down"
                                  ? "error"
                                  : "default"
                              }
                            >
                              {delta.label}
                            </Tag>
                            {index === 0 && <Tag color="blue">最新</Tag>}
                          </Space>
                          <Typography.Paragraph
                            type="secondary"
                            ellipsis={{ rows: 2 }}
                            className="sw-weekly-history-summary"
                          >
                            {item.progressSummary || "暂无进展说明"}
                          </Typography.Paragraph>
                        </div>
                      </List.Item>
                    );
                  }}
                />
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="暂无周进展记录"
                />
              )}
            </aside>
          </div>
        )}
      </Modal>
    </div>
  );
}
