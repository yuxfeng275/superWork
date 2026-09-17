package com.bu.management.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BuKeyMatterParticipantView {
    private Long userId;
    private String username;
    private String realName;
}
