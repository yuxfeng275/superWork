package com.bu.management.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BuKeyMatterAccessView {
    private boolean canAccess;
    private boolean canManageAll;
    private boolean canFeedbackOwn;
    private boolean canCreateOwn;
}
