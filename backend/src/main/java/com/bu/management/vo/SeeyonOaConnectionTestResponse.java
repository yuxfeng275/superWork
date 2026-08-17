package com.bu.management.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeeyonOaConnectionTestResponse {
    private boolean success;
    private String userName;
    private String memberName;
    private String message;
    private String testedAt;
}