package com.bu.management.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EmailAccountRequest {
  @NotBlank @Email private String emailAddress;
  private String appPassword;
  private Boolean enabled = true;
}
