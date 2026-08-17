package com.bu.management.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EmailWeComMappingRequest {
  @NotBlank private String weComUserId;
  private Boolean enabled = true;
}
