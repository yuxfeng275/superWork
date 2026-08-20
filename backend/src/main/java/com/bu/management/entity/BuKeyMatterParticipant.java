package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bu_key_matter_participant")
public class BuKeyMatterParticipant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long keyMatterId;
    private Long userId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
