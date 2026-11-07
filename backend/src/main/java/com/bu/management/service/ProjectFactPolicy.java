package com.bu.management.service;

import com.bu.management.entity.ProjectFact;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 项目事实的单一准入 / 可见性策略：谁能看、谁能写哪个项目的哪条事实，只在这里定义一次。
 *
 * <p>所有入口（网页投递列表 {@code ProjectFactIntakeController#list}、AI 工具
 * {@code project_facts} / {@code save_artifact} / {@code save_daily_brief}、制品接口、
 * 内部 token 端点）都必须走这里，不要再各自拼过滤条件——两套口径就是越权。
 *
 * <p>三个可见性口径（依据 {@code docs/fact-intake.md}）：
 * <ul>
 *   <li>{@code PROJECT}：项目成员（含客户侧参与人）可见，见 docs/fact-intake.md:129；</li>
 *   <li>{@code PRIVATE_COMPANY}：只有本公司的人可见，见 <b>docs/fact-intake.md:130</b>
 *       （内部成本 / 毛利 / 报价底价 / 对客户的内部评价 / 未对外排期）。判定取
 *       {@link ProjectFactAccessGuard#canWrite} 这一档：管理角色、项目负责人、项目成员、
 *       {@code project:manage} 权限持有者。<b>已知缺口</b>：{@code project_member} 目前没有
 *       「本公司 / 合作方」标记（见 ProjectMember 实体），客户侧参与人一旦进了 project_member
 *       会被这一档一并放行；补上成员内外标记前，合作方参与人的隔离只能靠不入成员表。</li>
 *   <li>{@code OWNER}：只有投递人本人（会议 v1 口径），见 docs/fact-intake.md:131。</li>
 * </ul>
 *
 * <p>本类只依赖 {@link ProjectFactAccessGuard}，不依赖 {@code ProjectFactService}，
 * 因此可以被事实服务反过来注入而不产生循环依赖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectFactPolicy {

    private final ProjectFactAccessGuard accessGuard;

    /** 能否读该项目的事实（项目成员 / 项目负责人 / 管理角色 / project:manage）。 */
    public boolean canRead(Long userId, Long projectId) {
        return accessGuard.canRead(userId, projectId);
    }

    /** 能否往该项目写事实（与读同一档）。 */
    public boolean canWrite(Long userId, Long projectId) {
        return accessGuard.canWrite(userId, projectId);
    }

    /**
     * PRIVATE_COMPANY 事实是否对该用户可见。
     *
     * <p>口径 = canWrite 这一档的人（管理角色 / 项目负责人 / 项目成员 / project:manage），
     * 依据 docs/fact-intake.md:130「只有本公司的人」；合作方参与人的隔离见类注释里的已知缺口。
     */
    public boolean canSeePrivateCompany(Long userId, Long projectId) {
        return accessGuard.canWrite(userId, projectId);
    }

    /**
     * 按可见性过滤：PROJECT 永远可见；PRIVATE_COMPANY 看 {@link #canSeePrivateCompany}；
     * OWNER 仅投递人本人（{@code actorId == userId}）；未标口径的老数据按 PROJECT 处理。
     *
     * <p>只在实际遇到 PRIVATE_COMPANY 事实时才做准入查询，避免空列表也打库。
     */
    public List<ProjectFact> visible(Long userId, Long projectId, List<ProjectFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        List<ProjectFact> kept = new ArrayList<>(facts.size());
        boolean companyChecked = false;
        boolean companyAllowed = false;
        for (ProjectFact fact : facts) {
            String visibility = fact.getVisibility();
            if (ProjectFactService.VISIBILITY_OWNER.equals(visibility)) {
                if (userId != null && userId.equals(fact.getActorId())) {
                    kept.add(fact);
                }
                continue;
            }
            if (ProjectFactService.VISIBILITY_COMPANY.equals(visibility)) {
                if (!companyChecked) {
                    companyAllowed = canSeePrivateCompany(userId, projectId);
                    companyChecked = true;
                }
                if (companyAllowed) {
                    kept.add(fact);
                }
                continue;
            }
            kept.add(fact);
        }
        return kept;
    }

    /**
     * 先判项目准入再过滤：读不通过就返回空列表而不抛异常——工具路径要把「没数据」和
     * 「没权限」分开表述，异常只会变成一句无从解释的失败。
     */
    public List<ProjectFact> visibleOrEmpty(Long userId, Long projectId, List<ProjectFact> facts) {
        if (!canRead(userId, projectId)) {
            log.debug("事实可见性：用户 {} 对项目 {} 无读权限，返回空列表", userId, projectId);
            return List.of();
        }
        return visible(userId, projectId, facts);
    }
}
