package com.bu.management.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.ConvertMeetingTodoInput;
import com.bu.management.dto.MeetingSpeakerRequest;
import com.bu.management.dto.MeetingTodoUpdateInput;
import com.bu.management.dto.MeetingTranscriptRequest;
import com.bu.management.entity.Meeting;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.service.MeetingAudioStorage;
import com.bu.management.service.MeetingConfigService;
import com.bu.management.service.MeetingPipelineService;
import com.bu.management.service.MeetingService;
import com.bu.management.service.MeetingTranscriptCodec;
import com.bu.management.vo.MeetingDetailView;
import com.bu.management.vo.MeetingListItem;
import com.bu.management.vo.MeetingSpeakerView;
import com.bu.management.vo.MeetingStatusView;
import com.bu.management.vo.MeetingTodoView;
import com.bu.management.vo.Result;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 会议模块 API：上传/列表/详情/状态、人工校正与说话人命名、总结重跑、待办编辑与转化、确认、音频回放、删除。
 * v1 可见范围 = 上传人本人（requireOwned）。
 */
@RestController
@RequestMapping("/api/meetings")
@RequiredArgsConstructor
@RequirePermission({"meeting:view"})
public class MeetingController {

    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("mp3", "wav", "m4a", "aac", "ogg", "flac", "mp4", "webm");
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "mp3", "audio/mpeg", "wav", "audio/wav", "m4a", "audio/mp4", "aac", "audio/aac",
            "ogg", "audio/ogg", "flac", "audio/flac", "mp4", "video/mp4", "webm", "video/webm");
    /** 与 application.yml 的 multipart 上限一致 */
    private static final long GLOBAL_MAX_BYTES = 100L * 1024 * 1024;

    private final MeetingService meetingService;
    private final MeetingPipelineService pipelineService;
    private final MeetingAudioStorage audioStorage;
    private final MeetingConfigService configService;

    @GetMapping
    public Result<Page<MeetingListItem>> list(
            @RequestAttribute("userId") Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {
        return Result.success(meetingService.list(userId, page, size, status));
    }

    @PostMapping
    @RequirePermission({"meeting:manage"})
    public Result<MeetingListItem> upload(
            @RequestAttribute("userId") Long userId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam("meetingDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate meetingDate,
            @RequestParam(value = "projectId", required = false) Long projectId) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("请选择录音文件");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new RuntimeException("不支持的音频格式：" + extension);
        }
        long maxBytes = Math.min(GLOBAL_MAX_BYTES,
                1024L * 1024 * Math.max(1, configService.load().audioMaxSizeMb()));
        if (file.getSize() > maxBytes) {
            throw new RuntimeException("录音超过大小上限（" + (maxBytes >> 20) + "MB）");
        }
        MeetingAudioStorage.StoredAudio stored;
        try (var in = file.getInputStream()) {
            stored = audioStorage.store(UUID.randomUUID().toString(), file.getOriginalFilename(), in);
        }
        MeetingListItem created;
        try {
            created = meetingService.create(userId, title, meetingDate, projectId, stored);
        } catch (RuntimeException e) {
            audioStorage.delete(stored.relativePath());
            throw e;
        }
        pipelineService.startProcessing(created.id());
        return Result.success(created);
    }

    @GetMapping("/{id}")
    public Result<MeetingDetailView> detail(@RequestAttribute("userId") Long userId, @PathVariable Long id) {
        return Result.success(meetingService.detail(userId, id));
    }

    @GetMapping("/{id}/status")
    public Result<MeetingStatusView> status(@RequestAttribute("userId") Long userId, @PathVariable Long id) {
        return Result.success(meetingService.status(userId, id));
    }

    /** 音频回放：owner 校验后流式返回，不做 Range。 */
    @GetMapping("/{id}/audio")
    public ResponseEntity<Resource> audio(@RequestAttribute("userId") Long userId, @PathVariable Long id) {
        Meeting meeting = meetingService.requireOwned(userId, id);
        Resource resource = new FileSystemResource(audioStorage.pathOf(meeting.getAudioFilePath()));
        if (!resource.exists()) {
            throw new ResourceNotFoundException("录音文件不存在");
        }
        String contentType = CONTENT_TYPES.getOrDefault(extensionOf(meeting.getAudioFilePath()),
                MediaType.APPLICATION_OCTET_STREAM_VALUE);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(resource);
    }

    @PutMapping("/{id}/transcript")
    @RequirePermission({"meeting:manage"})
    public Result<List<MeetingTranscriptCodec.Segment>> updateTranscript(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id,
            @RequestBody MeetingTranscriptRequest request) {
        return Result.success(meetingService.updateTranscript(userId, id,
                request == null ? List.of() : request.segments()));
    }

    @PutMapping("/{id}/speakers")
    @RequirePermission({"meeting:manage"})
    public Result<List<MeetingSpeakerView>> updateSpeakers(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id,
            @RequestBody MeetingSpeakerRequest request) {
        return Result.success(meetingService.updateSpeakers(userId, id,
                request == null ? List.of() : request.speakers()));
    }

    @PutMapping("/{id}/todos/{todoId}")
    @RequirePermission({"meeting:manage"})
    public Result<MeetingTodoView> updateTodo(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id,
            @PathVariable Long todoId,
            @RequestBody MeetingTodoUpdateInput input) {
        return Result.success(meetingService.updateTodo(userId, id, todoId, input));
    }

    @PostMapping("/{id}/todos/{todoId}/convert")
    @RequirePermission({"meeting:manage"})
    public Result<MeetingTodoView> convertTodo(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id,
            @PathVariable Long todoId,
            @RequestBody ConvertMeetingTodoInput input) {
        return Result.success(meetingService.convertTodo(userId, id, todoId, input));
    }

    @PostMapping("/{id}/summarize")
    @RequirePermission({"meeting:manage"})
    public Result<MeetingStatusView> summarize(@RequestAttribute("userId") Long userId, @PathVariable Long id) {
        meetingService.requireOwned(userId, id);
        return Result.success(pipelineService.summarize(id));
    }

    @PostMapping("/{id}/reprocess")
    @RequirePermission({"meeting:manage"})
    public Result<MeetingStatusView> reprocess(@RequestAttribute("userId") Long userId, @PathVariable Long id) {
        meetingService.requireOwned(userId, id);
        return Result.success(pipelineService.reprocess(id));
    }

    @PostMapping("/{id}/confirm")
    @RequirePermission({"meeting:manage"})
    public Result<MeetingStatusView> confirm(@RequestAttribute("userId") Long userId, @PathVariable Long id) {
        return Result.success(meetingService.confirm(userId, id));
    }

    @DeleteMapping("/{id}")
    @RequirePermission({"meeting:manage"})
    public Result<Void> delete(@RequestAttribute("userId") Long userId, @PathVariable Long id) {
        meetingService.delete(userId, id);
        return Result.success();
    }

    private static String extensionOf(String filename) {
        String name = filename == null ? "" : filename;
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
