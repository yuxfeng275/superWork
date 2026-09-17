package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 录音内匿名说话人的人工命名/映射（label 由 worker 输出，契约层面禁止身份推断）。
 */
@Data
@TableName("meeting_speaker")
public class MeetingSpeaker {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long meetingId;
    /** worker 输出的匿名标签 SPEAKER_00 */
    private String speakerLabel;
    /** 人工命名 */
    private String displayName;
    /** 人工映射到系统用户，仅展示用 */
    private Long mappedUserId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
