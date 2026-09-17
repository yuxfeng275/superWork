import { beforeEach, describe, expect, it, vi } from "vitest";
import { superworkApi } from "./api";

const jsonResponse = (data: unknown, status = 200) =>
  ({
    ok: status < 400,
    status,
    text: async () => JSON.stringify({ code: 200, message: "ok", data }),
    blob: async () => new Blob(["audio-bytes"]),
  }) as unknown as Response;

const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  localStorage.setItem("token", "test-token");
});

describe("superworkApi meeting endpoints", () => {
  it("getMeetings 传递分页与状态筛选参数", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ records: [], total: 0, size: 10, current: 1 })
    );
    vi.stubGlobal("fetch", fetchMock);

    await superworkApi.getMeetings({ page: 2, size: 10, status: "DRAFT" });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/meetings?page=2&size=10&status=DRAFT");
    expect(init.method).toBeUndefined();
  });

  it("uploadMeeting 使用 FormData 且不设置 Content-Type", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ id: 7, title: "周会", meetingDate: "2026-09-16" })
    );
    vi.stubGlobal("fetch", fetchMock);

    const file = new File(["audio"], "weekly.m4a", { type: "audio/mp4" });
    await superworkApi.uploadMeeting({
      file,
      title: "周会",
      meetingDate: "2026-09-16",
      projectId: 3,
    });

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/meetings");
    expect(init.method).toBe("POST");
    const body = init.body as FormData;
    expect(body).toBeInstanceOf(FormData);
    expect(body.get("file")).toBe(file);
    expect(body.get("title")).toBe("周会");
    expect(body.get("meetingDate")).toBe("2026-09-16");
    expect(body.get("projectId")).toBe("3");
    const headers = init.headers as Headers;
    expect(headers.has("Content-Type")).toBe(false);
    expect(headers.get("Authorization")).toBe("Bearer test-token");
  });

  it("updateMeetingTranscript 以 PUT 提交校正段", async () => {
    fetchMock.mockResolvedValue(jsonResponse([]));
    vi.stubGlobal("fetch", fetchMock);

    await superworkApi.updateMeetingTranscript(5, [
      { seq: 1, speaker: "SPEAKER_00", text: "开场" },
    ]);

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/meetings/5/transcript");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual({
      segments: [{ seq: 1, speaker: "SPEAKER_00", text: "开场" }],
    });
  });

  it("convertMeetingTodo 在 requirementId 缺省时仍按契约提交", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 9 }));
    vi.stubGlobal("fetch", fetchMock);

    await superworkApi.convertMeetingTodo(5, 9, {
      actionType: "TASK",
      assigneeId: 2,
    });

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/meetings/5/todos/9/convert");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({
      actionType: "TASK",
      assigneeId: 2,
    });
  });

  it("getMeetingAudioBlob 携带 Bearer 并返回 blob", async () => {
    fetchMock.mockResolvedValue(jsonResponse(null));
    vi.stubGlobal("fetch", fetchMock);

    const blob = await superworkApi.getMeetingAudioBlob(5);

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/meetings/5/audio");
    const headers = init.headers as Headers;
    expect(headers.get("Authorization")).toBe("Bearer test-token");
    expect(blob).toBeInstanceOf(Blob);
  });

  it("deleteMeeting 使用 DELETE", async () => {
    fetchMock.mockResolvedValue(jsonResponse(null));
    vi.stubGlobal("fetch", fetchMock);

    await superworkApi.deleteMeeting(5);

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/meetings/5");
    expect(init.method).toBe("DELETE");
  });
});
