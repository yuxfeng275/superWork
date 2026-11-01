export type WeeklyState = {
  status: string;
  progress: number;
};

/**
 * 周进展保存前归一化：
 * - 状态/进度缺失时回退到已有周进展或事项当前值（用户清空数字框不再 400）
 * - 状态为「已完成」时进度固定 100
 * - 状态不是「已完成」时进度最多 99（从已完成切回其它状态时不再触发后端校验）
 */
export const resolveWeeklyState = (
  status: unknown,
  progress: unknown,
  fallbackStatus: string,
  fallbackProgress: number
): WeeklyState => {
  const nextStatus =
    String(status ?? "").trim() || String(fallbackStatus || "").trim() || "推进中";
  const parsed = Number(progress);
  const fallback = Number(fallbackProgress);
  const blank =
    progress === null ||
    progress === undefined ||
    (typeof progress === "string" && progress.trim() === "");
  const base =
    blank || Number.isNaN(parsed)
      ? Number.isFinite(fallback)
        ? fallback
        : 0
      : parsed;
  const normalized = Math.max(0, Math.min(100, Math.round(base)));
  return nextStatus === "已完成"
    ? { status: nextStatus, progress: 100 }
    : { status: nextStatus, progress: Math.min(99, normalized) };
};
