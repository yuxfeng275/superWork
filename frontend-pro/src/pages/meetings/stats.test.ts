import { describe, expect, it } from 'vitest';
import { computeSpeakerStats } from './stats';

describe('computeSpeakerStats', () => {
  it('按说话人聚合时长（秒，四舍五入）', () => {
    const stats = computeSpeakerStats([
      { speaker: 'SPEAKER_00', startMs: 0, endMs: 8320 },
      { speaker: 'SPEAKER_01', startMs: 8320, endMs: 12000 },
      { speaker: 'SPEAKER_00', startMs: 12000, endMs: 24000 },
    ]);
    expect(stats).toEqual([
      { speaker: 'SPEAKER_00', seconds: 20, ratio: 0.83 },
      { speaker: 'SPEAKER_01', seconds: 4, ratio: 0.17 },
    ]);
  });

  it('按秒数降序排列', () => {
    const stats = computeSpeakerStats([
      { speaker: 'SPEAKER_01', startMs: 0, endMs: 1000 },
      { speaker: 'SPEAKER_02', startMs: 1000, endMs: 5000 },
      { speaker: 'SPEAKER_00', startMs: 5000, endMs: 12000 },
    ]);
    expect(stats.map((row) => row.speaker)).toEqual([
      'SPEAKER_00',
      'SPEAKER_02',
      'SPEAKER_01',
    ]);
  });

  it('占比按总时长计算并保留 2 位小数', () => {
    const stats = computeSpeakerStats([
      { speaker: 'SPEAKER_00', startMs: 0, endMs: 7000 },
      { speaker: 'SPEAKER_01', startMs: 7000, endMs: 10000 },
    ]);
    expect(stats).toEqual([
      { speaker: 'SPEAKER_00', seconds: 7, ratio: 0.7 },
      { speaker: 'SPEAKER_01', seconds: 3, ratio: 0.3 },
    ]);
  });

  it('空输入返回空数组', () => {
    expect(computeSpeakerStats([])).toEqual([]);
  });
});
