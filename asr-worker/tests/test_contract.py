"""会议 ASR worker 的 HTTP 契约测试。

全部由 stub 引擎驱动，不导入 funasr / torch / modelscope：

    python -m pytest tests -q        # 仅需 requirements-dev.txt

真实模型链路（ffmpeg → SenseVoice → VAD/标点/CAM++）由部署阶段冒烟验证。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Iterator

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))  # 允许从任意目录运行 pytest

import app as app_module
from transcribe import (
    FunasrEngine,
    Segment,
    TranscriptionError,
    TranscriptionResult,
    distribute_sentences,
    segments_from_sentences,
)

SEGMENTS = [
    Segment(index=1, start_ms=0, end_ms=8320, speaker="SPEAKER_00", text="大家好，开始今天的会议。"),
    Segment(index=2, start_ms=8320, end_ms=15210, speaker="SPEAKER_01", text="我先同步一下进度。"),
]
SAMPLE_RESULT = TranscriptionResult(duration_ms=3725000, model="SenseVoiceSmall", segments=SEGMENTS)
AUDIO_BYTES = b"\x1aE\xdf\xa3fake-webm-payload" * 32


class StubEngine:
    """按契约返回固定结果的假引擎，可注入失败。"""

    def __init__(self, result: TranscriptionResult = SAMPLE_RESULT, error: Exception | None = None) -> None:
        self.result = result
        self.error = error
        self.calls: list[dict] = []

    def transcribe(self, audio_path: Path, hotword: str | None = None) -> TranscriptionResult:
        path = Path(audio_path)
        # 临时目录在请求结束时删除，内容必须在调用内读取
        self.calls.append({"path": path, "hotword": hotword, "content": path.read_bytes()})
        if self.error is not None:
            raise self.error
        return self.result


@pytest.fixture()
def engine() -> StubEngine:
    return StubEngine()


class FakePipeline:
    """记录 generate 参数的最小管线替身；reject_hotword 模拟不支持热词的引擎。"""

    def __init__(self, results: list[dict], reject_hotword: bool = False) -> None:
        self.results = results
        self.reject_hotword = reject_hotword
        self.calls: list[dict] = []

    def generate(self, input, **kwargs):
        self.calls.append(kwargs)
        if kwargs.get("hotword") and self.reject_hotword:
            raise TypeError("generate() got an unexpected keyword argument 'hotword'")
        return self.results


class FakeVad:
    def __init__(self, value: list[list[int]]) -> None:
        self.value = value

    def generate(self, input, **kwargs):
        return [{"value": self.value}]


@pytest.fixture()
def worker_client(monkeypatch) -> Iterator:
    """工厂 fixture：把 stub 引擎注入 FastAPI 并按需配置 MEETING_TOKEN。"""
    created: list[TestClient] = []

    def build(engine: StubEngine, token: str | None = None, raise_server_exceptions: bool = True) -> TestClient:
        monkeypatch.delenv("MEETING_TOKEN", raising=False)
        if token is not None:
            monkeypatch.setenv("MEETING_TOKEN", token)
        app_module.app.dependency_overrides[app_module.get_engine] = lambda: engine
        client = TestClient(app_module.app, raise_server_exceptions=raise_server_exceptions)
        created.append(client)
        return client

    yield build
    for client in created:
        client.close()
    app_module.app.dependency_overrides.clear()


def upload(client: TestClient, *, hotword: str | None = None, headers: dict | None = None):
    data = {} if hotword is None else {"hotword": hotword}
    return client.post(
        "/v1/transcriptions",
        files={"audio": ("meeting.m4a", AUDIO_BYTES, "audio/mp4")},
        data=data,
        headers=headers or {},
    )


def test_healthz(worker_client) -> None:
    response = worker_client(StubEngine()).get("/healthz")
    assert response.status_code == 200
    assert response.json() == {"ok": True}


def test_transcription_response_matches_contract(worker_client, engine) -> None:
    response = upload(worker_client(engine))

    assert response.status_code == 200
    body = response.json()
    assert body["durationMs"] == 3725000
    assert body["model"] == "SenseVoiceSmall"
    assert [segment["index"] for segment in body["segments"]] == [1, 2]
    assert [segment["startMs"] for segment in body["segments"]] == [0, 8320]
    assert [segment["speaker"] for segment in body["segments"]] == ["SPEAKER_00", "SPEAKER_01"]
    for segment in body["segments"]:
        assert set(segment) == {"index", "startMs", "endMs", "speaker", "text"}
        assert re.fullmatch(r"SPEAKER_\d{2}", segment["speaker"])
        assert segment["endMs"] >= segment["startMs"] >= 0
        assert segment["text"]


def test_uploaded_bytes_reach_engine(worker_client, engine) -> None:
    upload(worker_client(engine))

    assert engine.calls[0]["content"] == AUDIO_BYTES
    assert engine.calls[0]["hotword"] is None


def test_hotword_is_forwarded(worker_client, engine) -> None:
    upload(worker_client(engine), hotword="CDP MA BI")

    assert engine.calls[0]["hotword"] == "CDP MA BI"


def test_blank_hotword_is_normalised_to_none(worker_client, engine) -> None:
    upload(worker_client(engine), hotword="   ")

    assert engine.calls[0]["hotword"] is None


def test_missing_audio_is_rejected_with_error_envelope(worker_client) -> None:
    response = worker_client(StubEngine()).post("/v1/transcriptions", data={"hotword": "CDP"})

    assert 400 <= response.status_code < 500
    error = response.json()["error"]
    assert error["code"] == "INVALID_REQUEST"
    assert error["message"]


def test_pipeline_failure_returns_error_envelope(worker_client) -> None:
    engine = StubEngine(
        error=TranscriptionError("AUDIO_CONVERT_FAILED", "音频转码失败：Invalid data found", status_code=400)
    )
    response = upload(worker_client(engine))

    assert response.status_code == 400
    assert response.json() == {
        "error": {"code": "AUDIO_CONVERT_FAILED", "message": "音频转码失败：Invalid data found"}
    }


def test_unexpected_failure_returns_internal_error_envelope(worker_client) -> None:
    engine = StubEngine(error=RuntimeError("boom"))
    response = upload(worker_client(engine, raise_server_exceptions=False))

    assert response.status_code == 500
    assert response.json()["error"]["code"] == "INTERNAL_ERROR"


def test_token_enforced_only_when_configured(worker_client, engine) -> None:
    unguarded = worker_client(engine)
    assert upload(unguarded).status_code == 200

    guarded = worker_client(engine, token="meeting-secret")
    assert upload(guarded).status_code == 401
    wrong = upload(guarded, headers={"X-Meeting-Token": "nope"})
    assert wrong.status_code == 401
    assert wrong.json()["error"] == {"code": "UNAUTHORIZED", "message": "无效的会议转写令牌"}
    assert upload(guarded, headers={"X-Meeting-Token": "meeting-secret"}).status_code == 200
    # 鉴权失败不触达引擎：只有两次成功请求
    assert len(engine.calls) == 2


def test_segments_from_sentences_maps_speakers_by_first_appearance() -> None:
    sentences = [
        {"start": 0, "end": 1000, "spk": 7, "text": "<|zh|><|NEUTRAL|>第一个说话人"},
        {"start": 1000, "end": 2000, "spk": 3, "sentence": "第二个说话人"},
        {"start": 2000, "end": 3000, "spk": 7, "text": "回到第一位"},
        {"start": 3000, "end": 4000, "spk": 3, "text": "   "},
    ]

    segments = segments_from_sentences(sentences)

    assert [segment.index for segment in segments] == [1, 2, 3]
    assert [segment.speaker for segment in segments] == ["SPEAKER_00", "SPEAKER_01", "SPEAKER_00"]
    assert "<|" not in segments[0].text and "第一个说话人" in segments[0].text
    assert (segments[0].start_ms, segments[0].end_ms) == (0, 1000)


def test_distribute_sentences_stays_inside_speech_bounds() -> None:
    bounds = [(0, 4000), (10_000, 12_000)]

    rows = distribute_sentences(["第一句话。", "第二句话。", "第三句话。"], bounds)

    assert [sentence for _, _, sentence in rows] == ["第一句话。", "第二句话。", "第三句话。"]
    assert rows[0][0] == 0
    assert all(start <= end for start, end, _ in rows)
    assert all(end <= next_start for (_, end, _), (next_start, _, _) in zip(rows, rows[1:]))
    # 每句都落在某个 VAD 语音段内（不跨静音区）
    assert all(
        any(window_start <= start and end <= window_end for window_start, window_end in bounds)
        for start, end, _ in rows
    )
    assert distribute_sentences([], bounds) == []
    assert distribute_sentences(["孤句"], []) == []


def test_hotword_is_dropped_when_engine_rejects_it() -> None:
    engine = FunasrEngine(model_dir="/tmp/asr-worker-test-models")
    pipeline = FakePipeline(results=[{"sentence_info": []}], reject_hotword=True)
    engine._pipeline = pipeline

    engine._generate(Path("unused.wav"), "CDP MA")

    assert pipeline.calls == [{"batch_size_s": 300, "hotword": "CDP MA"}, {"batch_size_s": 300}]


def test_vad_bounds_carry_fallback_segments_without_sentence_timestamps() -> None:
    engine = FunasrEngine(model_dir="/tmp/asr-worker-test-models")
    engine._pipeline = FakePipeline(results=[{"text": "第一句话。第二句话。", "timestamp": []}])
    engine._vad_model = FakeVad([[0, 2000], [5000, 8000]])

    segments = engine._transcribe_wav(Path("unused.wav"), 8000, None)

    assert [segment.text for segment in segments] == ["第一句话。", "第二句话。"]
    assert [segment.speaker for segment in segments] == ["SPEAKER_00", "SPEAKER_00"]
    assert [segment.index for segment in segments] == [1, 2]
    assert segments[0].start_ms == 0
    assert segments[-1].end_ms <= 8000
