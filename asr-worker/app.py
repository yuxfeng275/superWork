"""会议 ASR worker 的 HTTP 层（FastAPI + uvicorn）。

与 backend `MeetingTranscriptionClient` 对齐的契约：

- ``GET  /healthz``           → ``{"ok": true}``（无需鉴权，供容器 healthcheck 使用）
- ``POST /v1/transcriptions`` → multipart 字段 ``audio``（必填，文件）与 ``hotword``（可选）；
  header ``X-Meeting-Token``（仅当环境变量 ``MEETING_TOKEN`` 非空时校验）；
  200 → ``{"durationMs":…,"model":…,"segments":[{"index","startMs","endMs","speaker","text"}]}``；
  错误 → ``{"error":{"code":…,"message":…}}``。

同步处理：模型推理可达数十分钟，超时由调用方控制；单进程内串行推理，避免模型实例并发叠加内存。
"""

from __future__ import annotations

import hmac
import logging
import os
import tempfile
import threading
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, Header, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from transcribe import TranscriptionEngine, TranscriptionError

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("asr-worker")

app = FastAPI(title="meeting-asr-worker")

_CHUNK_SIZE = 1024 * 1024

_engine_lock = threading.Lock()
_transcription_lock = threading.Lock()
_engine: TranscriptionEngine | None = None


def get_engine() -> TranscriptionEngine:
    """惰性加载模型管线（首次请求时构建，进程内复用）；测试用 dependency_overrides 注入 stub。"""
    global _engine
    if _engine is None:
        with _engine_lock:
            if _engine is None:
                from transcribe import FunasrEngine  # 延迟导入：契约测试不需要模型栈

                _engine = FunasrEngine()
    return _engine


@app.get("/healthz")
def healthz() -> dict[str, bool]:
    return {"ok": True}


@app.post("/v1/transcriptions")
def create_transcription(
    audio: UploadFile = File(...),
    hotword: str | None = Form(default=None),
    x_meeting_token: str | None = Header(default=None),
    engine: TranscriptionEngine = Depends(get_engine),
) -> JSONResponse:
    require_token(x_meeting_token)
    with tempfile.TemporaryDirectory(prefix="meeting-asr-") as workspace:
        source = persist_upload(audio, Path(workspace))
        logger.info(
            "开始转写：file=%s size=%s hotword=%s",
            audio.filename,
            source.stat().st_size,
            bool((hotword or "").strip()),
        )
        with _transcription_lock:
            result = engine.transcribe(source, hotword=(hotword or "").strip() or None)
    return JSONResponse(content=result.to_dict())


@app.exception_handler(TranscriptionError)
async def transcription_error_handler(request: Request, exc: TranscriptionError) -> JSONResponse:
    logger.warning("转写失败[%s]：%s", exc.code, exc.message)
    return error_response(exc.status_code, exc.code, exc.message)


@app.exception_handler(RequestValidationError)
async def validation_error_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    return error_response(400, "INVALID_REQUEST", format_validation_error(exc))


@app.exception_handler(Exception)
async def unexpected_error_handler(request: Request, exc: Exception) -> JSONResponse:
    logger.exception("转写服务内部错误")
    return error_response(500, "INTERNAL_ERROR", f"转写服务内部错误：{exc}"[:500])


def require_token(provided: str | None) -> None:
    """仅当配置了 MEETING_TOKEN 时校验；比较用常量时间避免时序侧信道。"""
    expected = (os.environ.get("MEETING_TOKEN") or "").strip()
    if not expected:
        return
    if provided is None or not hmac.compare_digest(provided.encode("utf-8"), expected.encode("utf-8")):
        raise TranscriptionError("UNAUTHORIZED", "无效的会议转写令牌", status_code=401)


def persist_upload(upload: UploadFile, directory: Path) -> Path:
    """把上传流式落盘到本次请求的临时目录，保留扩展名便于 ffmpeg 探测。"""
    destination = directory / f"input{upload_suffix(upload)}"
    with destination.open("wb") as handle:
        while chunk := upload.file.read(_CHUNK_SIZE):
            handle.write(chunk)
    return destination


def upload_suffix(upload: UploadFile) -> str:
    suffix = Path(upload.filename or "").suffix
    if not suffix or len(suffix) > 16 or not suffix[1:].isalnum():
        return ".bin"
    return suffix


def error_response(status_code: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status_code, content={"error": {"code": code, "message": message}})


def format_validation_error(exc: RequestValidationError) -> str:
    problems = []
    for error in exc.errors()[:3]:
        location = ".".join(str(part) for part in error.get("loc", ()) if part != "body")
        detail = str(error.get("msg", "invalid"))
        problems.append(f"{location}: {detail}" if location else detail)
    return "请求参数不合法：" + "；".join(problems) if problems else "请求参数不合法"
