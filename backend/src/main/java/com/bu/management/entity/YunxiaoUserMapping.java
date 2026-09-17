package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("yunxiao_user_mapping")
public class YunxiaoUserMapping {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String yunxiaoUserId;
    private Integer syncEnabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
