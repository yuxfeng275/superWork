import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  getMeetings: vi.fn(),
  getMeeting: vi.fn(),
  getMeetingStatus: vi.fn(),
  uploadMeeting: vi.fn(),
  updateMeetingTranscript: vi.fn(),
  updateMeetingSpeakers: vi.fn(),
  summarizeMeeting: vi.fn(),
  reprocessMeeting: vi.fn(),
  updateMeetingTodo: vi.fn(),
  convertMeetingTodo: vi.fn(),
  confirmMeeting: vi.fn(),
  deleteMeeting: vi.fn(),
  getMeetingAudioBlob: vi.fn(),
  getProjects: vi.fn(),
  getUsers: vi.fn(),
  getProjectTree: vi.fn(),
  getRequirements: vi.fn(),
  getProjectMembers: vi.fn(),
}));

const pieProbe = vi.hoisted(() => ({
  props: undefined as { data?: Array<{ x: string; y: number }> } | undefined,
}));

vi.mock('@umijs/max', () => ({ history: { push: mocks.push } }));

vi.mock('@ant-design/plots', () => ({
  Pie: (props: { data?: Array<{ x: string; y: number }> }) => {
    pieProbe.props = props;
    return <div data-testid="speaker-pie" />;
  },
}));

vi.mock('@/services/superwork/api', () => ({
  superworkApi: {
    getMeetings: mocks.getMeetings,
    getMeeting: mocks.getMeeting,
    getMeetingStatus: mocks.getMeetingStatus,
    uploadMeeting: mocks.uploadMeeting,
    updateMeetingTranscript: mocks.updateMeetingTranscript,
    updateMeetingSpeakers: mocks.updateMeetingSpeakers,
    summarizeMeeting: mocks.summarizeMeeting,
    reprocessMeeting: mocks.reprocessMeeting,
    updateMeetingTodo: mocks.updateMeetingTodo,
    convertMeetingTodo: mocks.convertMeetingTodo,
    confirmMeeting: mocks.confirmMeeting,
    deleteMeeting: mocks.deleteMeeting,
    getMeetingAudioBlob: mocks.getMeetingAudioBlob,
    getProjects: mocks.getProjects,
    getUsers: mocks.getUsers,
    getProjectTree: mocks.getProjectTree,
    getRequirements: mocks.getRequirements,
    getProjectMembers: mocks.getProjectMembers,
  },
}));

import MeetingsPage from './index';

const listRow = {
  id: 5,
  title: 'CDP 项目周会',
  meetingDate: '2026-09-16',
  durationSeconds: 3725,
  status: 'DRAFT' as const,
};

const detail = {
  id: 5,
  title: 'CDP 项目周会',
  meetingDate: '2026-09-16',
  durationSeconds: 3725,
  status: 'DRAFT' as const,
  generationModel: 'glm-5',
  segments: [
    {
      seq: 1,
      startMs: 0,
      endMs: 8320,
      speaker: 'SPEAKER_00',
      text: '开场介绍 CDP 与 MA 目标',
      edited: false,
    },
    {
      seq: 2,
      startMs: 8320,
      endMs: 20320,
      speaker: 'SPEAKER_01',
      text: '埋点方案再确认',
      edited: false,
    },
  ],
  speakers: [{ speakerLabel: 'SPEAKER_00', displayName: '张三' }],
  summary: {
    summary: '本次会议对齐交付节奏。',
    keywords: ['CDP', 'MA'],
    sections: [
      {
        title: '开场与目标对齐',
        content: '确认本期目标。',
        startMs: 0,
        endMs: 8320,
        segmentRefs: [1],
      },
    ],
    speakerPoints: [
      {
        speaker: 'SPEAKER_00',
        points: ['补充埋点文档'],
        segmentRefs: [1],
      },
    ],
    decisions: [
      {
        content: '本期只做 CDP',
        excerpt: '开场介绍 CDP 与 MA 目标',
        startMs: 0,
        segmentRefs: [1],
      },
    ],
    risks: [
      {
        content: 'MA 排期紧张',
        severity: '高',
        excerpt: '埋点方案再确认',
        startMs: 8320,
        segmentRefs: [2],
      },
    ],
  },
  todos: [
    {
      id: 9,
      title: '补充埋点方案',
      dueText: '下周五',
      dueDate: null,
      status: 'DRAFT' as const,
    },
  ],
};

beforeEach(() => {
  vi.clearAllMocks();
  pieProbe.props = undefined;
  window.history.pushState({}, '', '/meetings');
  mocks.getMeetings.mockResolvedValue({
    records: [listRow],
    total: 1,
    size: 10,
    current: 1,
  });
  mocks.getProjects.mockResolvedValue({
    records: [],
    total: 0,
    current: 1,
    size: 200,
  });
  mocks.getUsers.mockResolvedValue({
    records: [],
    total: 0,
    current: 1,
    size: 200,
  });
  mocks.getProjectTree.mockResolvedValue([]);
  mocks.getRequirements.mockResolvedValue({
    records: [],
    total: 0,
    current: 1,
    size: 200,
  });
  mocks.getProjectMembers.mockResolvedValue([]);
  mocks.getMeetingAudioBlob.mockResolvedValue(new Blob(['audio']));
  mocks.uploadMeeting.mockResolvedValue(listRow);
  mocks.convertMeetingTodo.mockResolvedValue({
    ...detail.todos[0],
    status: 'CREATED',
    actionType: 'TASK',
    targetId: 77,
    targetTitle: '补充埋点方案',
  });
});

describe('MeetingsPage 列表', () => {
  it('渲染列表并通过上传 Modal 以 FormData 提交', async () => {
    render(<MeetingsPage />);
    expect(await screen.findByText('CDP 项目周会')).toBeInTheDocument();
    expect(screen.getByText('草稿')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /上传录音/ }));
    const dialog = await screen.findByRole('dialog');
    const file = new File(['audio'], 'weekly.m4a', { type: 'audio/mp4' });
    const fileInput = dialog.querySelector(
      'input[type="file"]',
    ) as HTMLInputElement;
    fireEvent.change(fileInput, { target: { files: [file] } });
    fireEvent.change(screen.getByPlaceholderText('例如：CDP 项目周会'), {
      target: { value: '复盘会' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: /上\s*传/ }));

    await waitFor(() =>
      expect(mocks.uploadMeeting).toHaveBeenCalledWith({
        file,
        title: '复盘会',
        meetingDate: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
        projectId: undefined,
      }),
    );
  });

  it('轮询状态，终态后停止', async () => {
    vi.useFakeTimers();
    try {
      mocks.getMeetings
        .mockResolvedValueOnce({
          records: [
            {
              id: 5,
              title: 'CDP 项目周会',
              meetingDate: '2026-09-16',
              durationSeconds: null,
              status: 'TRANSCRIBING',
            },
          ],
          total: 1,
          size: 10,
          current: 1,
        })
        .mockResolvedValue({
          records: [listRow],
          total: 1,
          size: 10,
          current: 1,
        });
      mocks.getMeetingStatus.mockResolvedValue({ status: 'DRAFT' });

      render(<MeetingsPage />);
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
      expect(mocks.getMeetingStatus).not.toHaveBeenCalled();

      await act(async () => {
        await vi.advanceTimersByTimeAsync(3000);
      });
      expect(mocks.getMeetingStatus).toHaveBeenCalledTimes(1);
      expect(mocks.getMeetings).toHaveBeenCalledTimes(2);

      await act(async () => {
        await vi.advanceTimersByTimeAsync(12000);
      });
      expect(mocks.getMeetingStatus).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('MeetingsPage 详情', () => {
  beforeEach(() => {
    window.history.pushState({}, '', '/meetings/5');
    mocks.getMeeting.mockResolvedValue(detail);
  });

  it('渲染关键词、章节速览与发言统计图', async () => {
    render(<MeetingsPage />);

    expect(await screen.findByText('开场与目标对齐')).toBeInTheDocument();
    expect(screen.getByText('CDP')).toBeInTheDocument();
    expect(screen.getByText('MA')).toBeInTheDocument();
    expect(screen.getByTestId('speaker-pie')).toBeInTheDocument();
    // 说话人时长降序：SPEAKER_01 12s > 张三(SPEAKER_00) 8s
    expect(pieProbe.props?.data).toEqual([
      { x: 'SPEAKER_01', y: 12 },
      { x: '张三', y: 8 },
    ]);
    expect(screen.getByText('补充埋点文档')).toBeInTheDocument();
  });

  it('待办转任务在未选需求时以 requirementId: undefined 提交', async () => {
    render(<MeetingsPage />);
    expect(await screen.findByDisplayValue('补充埋点方案')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /转任务/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: /创\s*建/ }));

    await waitFor(() =>
      expect(mocks.convertMeetingTodo).toHaveBeenCalledWith(
        5,
        9,
        expect.objectContaining({
          actionType: 'TASK',
          requirementId: undefined,
        }),
      ),
    );
  });
});
