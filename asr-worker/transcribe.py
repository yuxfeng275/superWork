"""转写管线：ffmpeg 归一化 → FunASR（SenseVoiceSmall + VAD + 标点 + CAM++）→ 契约分段。

设计约束：
- funasr / modelscope / torch 一律在函数内部延迟导入，契约测试（tests/test_contract.py）
  用 stub 引擎运行，不需要安装模型栈。
- 输出只含录音内匿名说话人标签（SPEAKER_NN，按首次出现顺序编号），不做任何身份推断。
- 引擎拿不到句级时间戳时回退用 VAD 段边界承载分段与时间。
"""

from __future__ import annotations

import logging
import os
import re
import shutil
import subprocess
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Protocol, Sequence

logger = logging.getLogger(__name__)

MODEL_NAME = "SenseVoiceSmall"
SENSEVOICE_MODEL = "iic/SenseVoiceSmall"
VAD_MODEL = "iic/speech_fsmn_vad_zh-cn-16k-common-pytorch"
PUNCT_MODEL = "iic/punc_ct-transformer_cn-en-common-vocab471067-large"
SPEAKER_MODEL = "iic/speech_campplus_sv_zh-cn_16k-common"

DEFAULT_MODEL_DIR = "/models"
TARGET_SAMPLE_RATE = 16000
FFMPEG_TIMEOUT_SECONDS = 600
BATCH_SIZE_SECONDS = 300

_RICH_TAG_PATTERN = re.compile(r"<\|[^|]*\|>")
_SENTENCE_END_PATTERN = re.compile(r"(?<=[。！？!?；;…])")

_postprocessor: Callable[[str], str] | None = None


class TranscriptionError(Exception):
    """转写失败；app.py 直接按 ``code`` / ``status_code`` 回错误契约 JSON。"""

    def __init__(self, code: str, message: str, status_code: int = 500) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


@dataclass(frozen=True)
class Segment:
    index: int
    start_ms: int
    end_ms: int
    speaker: str
    text: str

    def to_dict(self) -> dict:
        return {
            "index": self.index,
            "startMs": self.start_ms,
            "endMs": self.end_ms,
            "speaker": self.speaker,
            "text": self.text,
        }


@dataclass(frozen=True)
class TranscriptionResult:
    duration_ms: int
    model: str
    segments: list[Segment]

    def to_dict(self) -> dict:
        return {
            "durationMs": self.duration_ms,
            "model": self.model,
            "segments": [segment.to_dict() for segment in self.segments],
        }


class TranscriptionEngine(Protocol):
    """转写引擎接口；替换引擎时只须实现该方法，HTTP 契约不变。"""

    def transcribe(self, audio_path: Path, hotword: str | None = None) -> TranscriptionResult:
        ...


def convert_to_wav(source: Path, destination: Path) -> None:
    """ffmpeg 预转 16 kHz 单声道 PCM wav；失败抛带明确信息的 TranscriptionError。"""
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise TranscriptionError("FFMPEG_MISSING", "容器内未安装 ffmpeg，请检查部署镜像", status_code=500)
    command = [
        ffmpeg,
        "-nostdin",
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-i",
        str(source),
        "-vn",
        "-ac",
        "1",
        "-ar",
        str(TARGET_SAMPLE_RATE),
        "-c:a",
        "pcm_s16le",
        str(destination),
    ]
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=FFMPEG_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired as exc:
        raise TranscriptionError(
            "AUDIO_CONVERT_FAILED",
            f"音频转码超时（>{FFMPEG_TIMEOUT_SECONDS}s）",
            status_code=400,
        ) from exc
    if completed.returncode != 0:
        lines = [line for line in (completed.stderr or "").strip().splitlines() if line.strip()]
        reason = lines[-1][:200] if lines else f"ffmpeg 退出码 {completed.returncode}"
        raise TranscriptionError("AUDIO_CONVERT_FAILED", f"音频转码失败：{reason}", status_code=400)


def wav_duration_ms(wav_path: Path) -> int:
    """读取转码后 wav 的时长，作为响应里的 durationMs。"""
    with wave.open(str(wav_path), "rb") as wav_file:
        frames = wav_file.getnframes()
        rate = wav_file.getframerate()
    if rate <= 0:
        raise TranscriptionError("AUDIO_CONVERT_FAILED", "转码结果缺少采样率", status_code=500)
    return int(round(frames * 1000 / rate))


def clean_text(text: str) -> str:
    """去掉 SenseVoice 富标签；模型栈缺失时退化为正则剥离，测试无需安装 funasr。"""
    global _postprocessor
    text = text.strip()
    if not text:
        return ""
    if _postprocessor is None:
        _postprocessor = _resolve_text_postprocessor()
    return _postprocessor(text).strip()


def segments_from_sentences(sentences: Iterable[dict]) -> list[Segment]:
    """FunASR sentence_info → 契约分段。

    说话人聚类 id 只在单次录音内有效，这里按首次出现顺序重映射为 SPEAKER_00、SPEAKER_01…
    （契约层面禁止身份推断）；缺失 spk 的引擎输出统一落到 SPEAKER_00。
    """
    labels: dict[str, str] = {}
    segments: list[Segment] = []
    for sentence in sentences:
        text = clean_text(str(sentence.get("text") or sentence.get("sentence") or ""))
        if not text:
            continue
        start_ms = as_millis(sentence.get("start"))
        end_ms = max(start_ms, as_millis(sentence.get("end")))
        raw_speaker = sentence.get("spk")
        speaker_key = "unknown" if raw_speaker is None else str(raw_speaker)
        if speaker_key not in labels:
            labels[speaker_key] = f"SPEAKER_{len(labels):02d}"
        segments.append(
            Segment(
                index=len(segments) + 1,
                start_ms=start_ms,
                end_ms=end_ms,
                speaker=labels[speaker_key],
                text=text,
            )
        )
    return segments


def split_sentences(text: str) -> list[str]:
    """按中英文句末标点切句，供 VAD 回退路径把整段文本分配到语音段边界上。"""
    return [part.strip() for part in _SENTENCE_END_PATTERN.split(text) if part.strip()]


def distribute_sentences(
    sentences: Sequence[str],
    bounds: Sequence[tuple[int, int]],
) -> list[tuple[int, int, str]]:
    """把句子按长度比例铺到有序的语音段边界上，返回 (startMs, endMs, text)。

    仅用于 VAD 回退路径：每句落在单个语音段内、不跨静音区，结果单调且不越出 bounds。
    """
    if not sentences or not bounds:
        return []
    speech_ms = sum(end - start for start, end in bounds)
    total_chars = sum(len(sentence) for sentence in sentences) or 1
    window_index = 0
    cursor = float(bounds[0][0])
    rows: list[tuple[int, int, str]] = []
    for sentence in sentences:
        window_end = float(bounds[window_index][1])
        if cursor >= window_end and window_index + 1 < len(bounds):
            window_index += 1
            cursor = float(bounds[window_index][0])
            window_end = float(bounds[window_index][1])
        start = cursor
        cursor = min(start + speech_ms * len(sentence) / total_chars, window_end)
        rows.append((int(round(start)), int(round(cursor)), sentence))
    return rows


class FunasrEngine:
    """SenseVoiceSmall + FSMN-VAD + 标点 + CAM++ 管线；模型按进程惰性加载并缓存。"""

    def __init__(self, model_dir: str | os.PathLike[str] | None = None) -> None:
        self._model_dir = Path(model_dir or os.environ.get("MODEL_DIR") or DEFAULT_MODEL_DIR)
        self._pipeline = None
        self._vad_model = None

    def transcribe(self, audio_path: Path, hotword: str | None = None) -> TranscriptionResult:
        wav_path = audio_path.parent / f"{audio_path.name}.16k.wav"
        convert_to_wav(audio_path, wav_path)
        duration_ms = wav_duration_ms(wav_path)
        segments = self._transcribe_wav(wav_path, duration_ms, hotword)
        if not segments:
            raise TranscriptionError("EMPTY_TRANSCRIPTION", "未能从录音中识别出任何语音内容", status_code=500)
        return TranscriptionResult(duration_ms=duration_ms, model=MODEL_NAME, segments=segments)

    def _transcribe_wav(self, wav_path: Path, duration_ms: int, hotword: str | None) -> list[Segment]:
        results = self._generate(wav_path, hotword)
        sentences = [sentence for result in results for sentence in (result.get("sentence_info") or [])]
        if sentences:
            return segments_from_sentences(sentences)
        logger.warning("引擎未返回句级时间戳，回退到 VAD 段边界")
        return self._segments_from_vad(wav_path, duration_ms, results)

    def _generate(self, wav_path: Path, hotword: str | None) -> list[dict]:
        pipeline = self._ensure_pipeline()
        call_kwargs: dict = {"batch_size_s": BATCH_SIZE_SECONDS}
        if hotword:
            # SenseVoice 不消费解码热词，透传后由引擎自行忽略；不支持的引擎会抛 TypeError，降级重试。
            call_kwargs["hotword"] = hotword
        try:
            return list(pipeline.generate(input=str(wav_path), **call_kwargs))
        except TypeError as exc:
            if "hotword" not in call_kwargs:
                raise
            logger.warning("当前引擎不接受 hotword 参数，忽略热词：%s", exc)
            call_kwargs.pop("hotword")
            return list(pipeline.generate(input=str(wav_path), **call_kwargs))

    def _segments_from_vad(self, wav_path: Path, duration_ms: int, results: list[dict]) -> list[Segment]:
        text = clean_text("".join(str(result.get("text") or "") for result in results))
        if not text:
            return []
        bounds = self._detect_speech_bounds(wav_path) or [(0, duration_ms)]
        sentences = split_sentences(text) or [text]
        rows = distribute_sentences(sentences, bounds)
        return [
            Segment(index=index, start_ms=start_ms, end_ms=end_ms, speaker="SPEAKER_00", text=sentence)
            for index, (start_ms, end_ms, sentence) in enumerate(rows, start=1)
        ]

    def _detect_speech_bounds(self, wav_path: Path) -> list[tuple[int, int]]:
        if self._vad_model is None:
            self._vad_model = build_vad_model(self._model_dir)
        bounds: list[tuple[int, int]] = []
        for result in self._vad_model.generate(input=str(wav_path)) or []:
            for interval in result.get("value") or []:
                start_ms, end_ms = as_millis(interval[0]), as_millis(interval[1])
                if end_ms > start_ms:
                    bounds.append((start_ms, end_ms))
        return bounds

    def _ensure_pipeline(self):
        if self._pipeline is None:
            self._pipeline = build_pipeline(self._model_dir)
        return self._pipeline


def build_pipeline(model_dir: Path):
    """构建 ASR 管线；模型首次使用时会下载到 MODEL_DIR（数 GB，构建一次后进程内复用）。"""
    from funasr import AutoModel  # 延迟导入：stub 契约测试不依赖 funasr/torch

    cache_dir = prepare_cache_dir(model_dir)
    logger.info("加载 ASR 管线：%s + VAD + 标点 + CAM++（缓存目录 %s）", SENSEVOICE_MODEL, cache_dir)
    return AutoModel(
        model=SENSEVOICE_MODEL,
        vad_model=VAD_MODEL,
        punc_model=PUNCT_MODEL,
        spk_model=SPEAKER_MODEL,
        spk_mode="punc_segment",
        vad_kwargs={"max_single_segment_time": 30000},
        device="cpu",
        disable_update=True,
        disable_pbar=True,
    )


def build_vad_model(model_dir: Path):
    """VAD 回退路径单独构建的小模型（FSMN-VAD）。"""
    from funasr import AutoModel  # 延迟导入：stub 契约测试不依赖 funasr/torch

    cache_dir = prepare_cache_dir(model_dir)
    logger.info("加载 VAD 模型 %s（缓存目录 %s）", VAD_MODEL, cache_dir)
    return AutoModel(model=VAD_MODEL, device="cpu", disable_update=True, disable_pbar=True)


def prepare_cache_dir(model_dir: Path) -> str:
    """确保 MODEL_DIR 存在，并让 modelscope / huggingface 的下载缓存落在该目录。"""
    try:
        model_dir.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        raise TranscriptionError("MODEL_DIR_UNAVAILABLE", f"模型目录不可写：{model_dir}（{exc}）", status_code=500) from exc
    os.environ.setdefault("MODELSCOPE_CACHE", str(model_dir))
    os.environ.setdefault("HF_HOME", str(model_dir))
    return str(model_dir)


def as_millis(value) -> int:
    """宽松转毫秒整数：模型/引擎输出可能是 float、numpy 标量或 None。"""
    try:
        millis = int(float(value))
    except (TypeError, ValueError):
        return 0
    return max(0, millis)


def _resolve_text_postprocessor() -> Callable[[str], str]:
    try:
        from funasr.utils.postprocess_utils import rich_transcription_postprocess
    except Exception:  # noqa: BLE001 - 模型栈缺失时退化为标签剥离
        logger.warning("未找到 rich_transcription_postprocess，退回正则剥离 SenseVoice 富标签")
        return lambda text: _RICH_TAG_PATTERN.sub("", text)
    return rich_transcription_postprocess
