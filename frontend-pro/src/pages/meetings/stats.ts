export interface SpeakerStatSegment {
  speaker: string;
  startMs: number;
  endMs: number;
}

export interface SpeakerStat {
  speaker: string;
  seconds: number;
  ratio: number;
}

/**
 * 会议发言时长统计：按说话人聚合转写段的时长（秒），按占比降序。
 * ratio 为四舍五入到 2 位小数的占比；空输入返回空数组。
 */
export function computeSpeakerStats(
  segments: SpeakerStatSegment[],
): SpeakerStat[] {
  if (!segments.length) return [];
  const totals = new Map<string, number>();
  segments.forEach((segment) => {
    const duration = Math.max(0, segment.endMs - segment.startMs);
    totals.set(segment.speaker, (totals.get(segment.speaker) ?? 0) + duration);
  });
  const rows = Array.from(totals, ([speaker, totalMs]) => ({
    speaker,
    seconds: Math.round(totalMs / 1000),
  }));
  const totalSeconds = rows.reduce((sum, row) => sum + row.seconds, 0);
  return rows
    .map((row) => ({
      ...row,
      ratio: totalSeconds
        ? Math.round((row.seconds / totalSeconds) * 100) / 100
        : 0,
    }))
    .sort((a, b) => b.seconds - a.seconds);
}
