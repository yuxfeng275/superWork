package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("user_todo")
public class UserTodo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long assigneeId;
    private Long actorId;
    private String sourceType;
    private Long sourceId;
    private String mentionToken;
    private String title;
    private String excerpt;
    private String link;
    private LocalDate sourceDate;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
