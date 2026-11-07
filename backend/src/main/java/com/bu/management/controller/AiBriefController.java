package com.bu.management.controller;

import com.bu.management.entity.AiBrief;
import com.bu.management.service.ProjectBriefService;
import com.bu.management.vo.Result;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的晨报：夜间产出汇总，默认今天。
 * 只读自己那份，不含他人（分发口径见 docs/project-agent-loop-plan.md §5.4）。
 */
@RestController
@RequestMapping("/api/ai-briefs")
@RequiredArgsConstructor
public class AiBriefController {

    private final ProjectBriefService briefService;

    @GetMapping
    public Result<List<AiBrief>> mine(@RequestAttribute("userId") Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.success(briefService.listByDate(userId, date));
    }
}
