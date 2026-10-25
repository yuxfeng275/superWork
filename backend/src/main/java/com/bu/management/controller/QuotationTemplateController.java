package com.bu.management.controller;

import com.bu.management.entity.QuotationTemplate;
import com.bu.management.entity.QuotationTemplateClause;
import com.bu.management.service.QuotationTemplateService;
import com.bu.management.vo.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/quotation-templates")
@RequiredArgsConstructor
public class QuotationTemplateController {

    private final QuotationTemplateService service;

    @GetMapping
    public Result<List<QuotationTemplate>> list(@RequestParam(required = false) String status) {
        return Result.success(service.list(status));
    }

    @GetMapping("/{id}")
    public Result<QuotationTemplate> get(@PathVariable Long id) {
        return Result.success(service.get(id));
    }

    @GetMapping("/{id}/clauses")
    public Result<List<QuotationTemplateClause>> clauses(@PathVariable Long id) {
        return Result.success(service.clauses(id));
    }

    @PostMapping
    public Result<QuotationTemplate> create(@RequestBody QuotationTemplate template) {
        template.setId(null);
        return Result.success(service.save(template));
    }

    @PutMapping("/{id}")
    public Result<QuotationTemplate> update(@PathVariable Long id, @RequestBody QuotationTemplate template) {
        template.setId(id);
        return Result.success(service.save(template));
    }

    @PutMapping("/{id}/clauses")
    public Result<Void> replaceClauses(@PathVariable Long id, @RequestBody List<QuotationTemplateClause> clauses) {
        service.replaceClauses(id, clauses);
        return Result.success();
    }

    @PutMapping("/{id}/publish")
    public Result<Void> publish(@PathVariable Long id) {
        service.publish(id);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.success();
    }

    /** 用一张报价单的数据渲染模板条款，供前端预览。 */
    @PutMapping("/{id}/preview")
    public Result<Map<String, List<String>>> preview(@PathVariable Long id,
                                                     @RequestBody(required = false) com.bu.management.entity.Quotation quotation) {
        return Result.success(service.render(id, quotation));
    }
}
