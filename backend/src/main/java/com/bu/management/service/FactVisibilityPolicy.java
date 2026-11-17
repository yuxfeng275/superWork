package com.bu.management.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 投递默认可见性策略：调用方没显式给 visibility 时，按文件名兜默认值。
 *
 * <p>规则与 {@code shared/privacy/sensitive-rules.json} 的服务端子集保持一致
 * （关键词 / 扩展名 / 固定文件名三组），两端改动要同步，别只改一边。
 * 只看文件名，不看路径：路径里出现「工资」目录不代表文件本身敏感，也不代表不敏感。
 *
 * <p>命中任一 → {@code OWNER}（仅投递人可见）；否则 {@code PROJECT}（项目成员可见）。
 * 显式传了 visibility 就一律以调用方为准，本类不覆盖。
 */
public final class FactVisibilityPolicy {

    /** 文件名关键词，大小写不敏感包含匹配。 */
    static final List<String> SENSITIVE_FILE_KEYWORDS = List.of(
            "身份证", "银行卡", "工资", "薪资", "劳动合同", "简历", "体检", "密码",
            "社保", "公积金", "个税", "离职", "offer", "背调", "病例", "诊断");

    /** 敏感扩展名（小写，含点）。 */
    static final Set<String> SENSITIVE_EXTENSIONS = Set.of(
            ".pem", ".key", ".p12", ".pfx", ".kdbx", ".env", ".ovpn", ".jks", ".keystore");

    /** 敏感固定文件名（小写，不含扩展名匹配，如 id_rsa / id_rsa.pub 都算）。 */
    static final Set<String> SENSITIVE_NAMES = Set.of(
            "id_rsa", "id_ed25519", "credentials", "passwords");

    private FactVisibilityPolicy() {
    }

    /**
     * 按文件名判定默认可见性。
     *
     * @param fileName 原始文件名（可带路径、可为空）
     * @return {@code OWNER} 或 {@code PROJECT}；文件名为空按 {@code PROJECT}
     */
    public static String defaultVisibility(String fileName) {
        return isSensitiveFileName(fileName)
                ? ProjectFactService.VISIBILITY_OWNER
                : ProjectFactService.VISIBILITY_PROJECT;
    }

    /** 文件名是否踩中敏感规则（只看文件名本体，大小写不敏感）。 */
    public static boolean isSensitiveFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return false;
        }
        String name = baseName(fileName);
        if (name.isEmpty()) {
            return false;
        }
        for (String keyword : SENSITIVE_FILE_KEYWORDS) {
            if (name.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && SENSITIVE_EXTENSIONS.contains(name.substring(dot))) {
            return true;
        }
        if (SENSITIVE_NAMES.contains(name)) {
            return true;
        }
        return dot > 0 && SENSITIVE_NAMES.contains(name.substring(0, dot));
    }

    /** 去掉路径、统一小写；只保留文件名本体。 */
    private static String baseName(String fileName) {
        String name = fileName.strip();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        return name.toLowerCase(Locale.ROOT);
    }
}
