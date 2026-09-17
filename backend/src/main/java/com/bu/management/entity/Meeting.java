package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 会议录音与纪要：transcript_json 不可变，人工校正另存 corrected_transcript_json。
 */
@Data
@TableName("meeting")
public class Meeting {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 上传人（v1 可见范围=上传人） */
    private Long ownerUserId;
    private String title;
    private LocalDate meetingDate;
    private Long projectId;
    private Integer durationSeconds;
    private String audioFilePath;
    private Long audioSizeBytes;
    private String audioSha256;
    /** UPLOADED/TRANSCRIBING/SUMMARIZING/DRAFT/CONFIRMED/FAILED */
    private String status;
    /** 原始转写（不可变） */
    private String transcriptJson;
    /** 人工校正稿 */
    private String correctedTranscriptJson;
    private String summaryJson;
    private String generationModel;
    private String generationError;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
