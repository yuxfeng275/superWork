package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.config.ProjectContextProperties;
import com.bu.management.entity.ProjectFact;
import com.bu.management.mapper.BuKeyMatterMapper;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.mapper.MeetingMapper;
import com.bu.management.mapper.ProjectFactMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.YunxiaoWorkitemCacheMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 投递入口：同一文件重传只一条事实、聊天批量写一条且保留每句、可见性缺省为 PROJECT。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectFactIntakeServiceTest {

    @Mock private ProjectFactMapper factMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private MeetingMapper meetingMapper;
    @Mock private EmailMessageMapper emailMessageMapper;
    @Mock private YunxiaoWorkitemCacheMapper workitemCacheMapper;
    @Mock private BuKeyMatterMapper keyMatterMapper;
    @Mock private ProjectFactAccessGuard projectFactAccessGuard;

    @TempDir Path tempDir;

    private ProjectFactStorage storage;
    private ProjectFactIntakeService intakeService;

    /** 内存里的一条事实：模拟 dedupe_hash 唯一约束。 */
    private final AtomicReference<ProjectFact> stored = new AtomicReference<>();
    private final AtomicLong idSeq = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        ProjectContextProperties properties = new ProjectContextProperties();
        properties.setFactStorageDir(tempDir.resolve("facts").toString());
        properties.setFactMaxFileMb(50);
        properties.setMaxSourceRows(50);
        storage = new ProjectFactStorage(properties);
        ProjectFactService factService = new ProjectFactService(factMapper, projectMapper,
                meetingMapper, emailMessageMapper, workitemCacheMapper, keyMatterMapper, properties,
                new ProjectFactPolicy(projectFactAccessGuard));
        intakeService = new ProjectFactIntakeService(factService, storage);

        when(factMapper.insert(any(ProjectFact.class))).thenAnswer(inv -> {
            ProjectFact fact = inv.getArgument(0);
            fact.setId(idSeq.incrementAndGet());
            stored.set(fact);
            return 1;
        });
        when(factMapper.updateById(any(ProjectFact.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return 1;
        });
        when(factMapper.selectOne(any())).thenAnswer(inv -> stored.get());
    }

    @Test
    void sameFileTwiceWritesOnlyOneFact() throws Exception {
        byte[] content = "会议纪要第一行\n第二行".getBytes(StandardCharsets.UTF_8);

        ProjectFact first = intakeService.intakeFile(11L, 3L, "纪要.md",
                new ByteArrayInputStream(content), null, null, null);
        ProjectFact second = intakeService.intakeFile(11L, 3L, "纪要.md",
                new ByteArrayInputStream(content), null, null, null);

        // 同一文件（sha256 相同）只插入一次，第二次走更新
        verify(factMapper, times(1)).insert(any(ProjectFact.class));
        verify(factMapper, times(1)).updateById(any(ProjectFact.class));
        assertThat(first.getSourceRef())
                .startsWith("file:11:")
                .isEqualTo(second.getSourceRef());
        assertThat(second.getSourceType()).isEqualTo(ProjectFactService.SOURCE_FILE_UPLOAD);
        // 文本类文件抽头部正文
        assertThat(second.getBody()).contains("会议纪要第一行");
        assertThat(second.getTitle()).isEqualTo("纪要.md");
        // 落盘文件名 = sha256 前 16 位 + 扩展名
        String name = second.getSourceRef().substring(second.getSourceRef().lastIndexOf(':') + 1);
        Path disk = tempDir.resolve("facts").resolve("11").resolve(name.substring(0, 16) + ".md");
        assertThat(Files.exists(disk)).isTrue();
        assertThat(Files.size(disk)).isEqualTo(content.length);
    }

    @Test
    void chatBatchWritesOneFactKeepingEveryLine() {
        List<ProjectFactIntakeService.ChatMessage> messages = List.of(
                new ProjectFactIntakeService.ChatMessage("张三", "2026-10-23 10:00", "排期今天定"),
                new ProjectFactIntakeService.ChatMessage("李四", "2026-10-23 10:05", "明天给客户答复"));

        ProjectFact fact = intakeService.intakeChat(11L, 3L, messages, null);

        assertThat(fact.getSourceType()).isEqualTo(ProjectFactService.SOURCE_CHAT_EXPORT);
        assertThat(fact.getTitle()).isEqualTo("聊天记录 2 条");
        assertThat(fact.getSourceRef()).startsWith("chat:11:");
        assertThat(fact.getBody())
                .contains("[2026-10-23 10:00] 张三: 排期今天定")
                .contains("[2026-10-23 10:05] 李四: 明天给客户答复");
        verify(factMapper, times(1)).insert(any(ProjectFact.class));

        // 同一段记录重传不新增
        intakeService.intakeChat(11L, 3L, messages, null);
        verify(factMapper, times(1)).insert(any(ProjectFact.class));
        verify(factMapper, times(1)).updateById(any(ProjectFact.class));
    }

    @Test
    void visibilityDefaultsToProject() {
        ProjectFact fact = intakeService.intakeNote(11L, 3L, "客户口头要求", "周五前交付", null);

        assertThat(fact.getVisibility()).isEqualTo(ProjectFactService.VISIBILITY_PROJECT);
        assertThat(fact.getSourceType()).isEqualTo(ProjectFactService.SOURCE_NOTE);
        assertThat(fact.getSourceRef()).startsWith("note:");
        assertThat(fact.getDigest()).contains("客户口头要求");

        ProjectFact company = intakeService.intakeNote(11L, 3L, "内部口径", null, "PRIVATE_COMPANY");
        assertThat(company.getVisibility()).isEqualTo(ProjectFactService.VISIBILITY_COMPANY);
    }

    @Test
    void noteRequiresTitleAndChatRequiresMessages() {
        assertThatThrownBy(() -> intakeService.intakeNote(11L, 3L, " ", "正文", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标题");
        assertThatThrownBy(() -> intakeService.intakeChat(11L, 3L, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("聊天记录");
    }
}
