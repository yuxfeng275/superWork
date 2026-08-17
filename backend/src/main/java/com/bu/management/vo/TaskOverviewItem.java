package com.bu.management.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Task overview item. Local and Yunxiao tasks share the common work-item contract.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TaskOverviewItem extends WorkItemOverviewItem {
}
