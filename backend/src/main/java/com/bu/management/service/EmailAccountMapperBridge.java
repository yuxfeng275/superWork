package com.bu.management.service;

import com.bu.management.mapper.EmailAccountMapper;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class EmailAccountMapperBridge {
  public record Owner(Long accountId, Long ownerUserId) {}

  private final EmailAccountMapper mapper;

  public EmailAccountMapperBridge(EmailAccountMapper mapper) {
    this.mapper = mapper;
  }

  public List<Owner> enabledOwners() {
    return mapper.selectEnabledAccounts().stream()
        .map(a -> new Owner(a.getId(), a.getOwnerUserId()))
        .toList();
  }
}
