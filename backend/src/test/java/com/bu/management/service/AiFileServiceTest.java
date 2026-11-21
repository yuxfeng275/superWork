package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.AiFile;
import com.bu.management.entity.ProjectFact;
import com.bu.management.mapper.AiFileMapper;
import com.bu.management.service.objectstore.LocalObjectStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 统一空间的投递契约：一份文件只存一次原文、只产生一条事实、无权限直接拒绝。
 *
 * <p>用真实的 {@link LocalObjectStore}（临时目录）验证「原文确实落到了键上」，
 * 其余依赖用 mock：这里要证明的是流程与时序，不是 MyBatis 映射。
 */
@ExtendWith(MockitoExtension.class)
class AiFileServiceTest {

    @Mock private AiFileMapper fileMapper;
    @Mock private ProjectFactAccessGuard accessGuard;
    @Mock private ProjectFactService factService;

    @TempDir Path tempDir;

    private AiFileService service;

    @BeforeEach
    void setUp() {
        service = new AiFileService(fileMapper, new LocalObjectStore(tempDir), accessGuard, factService);
        ReflectionTestUtils.setField(service, "maxFileMb", 5);
    }

    @Test
    @DisplayName("首次投递：落原文 + 插入文件行 + 写 FILE_UPLOAD 事实")
    void firstUploadStoresFileAndFact() throws Exception {
        when(accessGuard.canWrite(7L, 1L)).thenReturn(true);
        when(fileMapper.selectOne(any())).thenReturn(null);
        insertAssignsId(11L);
        when(factService.upsertFileFact(eq(1L), eq(7L), eq(11L), anyString(), anyString(),
                any(), eq(ProjectFactService.VISIBILITY_PROJECT))).thenReturn(1);
        ProjectFact fact = new ProjectFact();
        fact.setId(99L);
        when(factService.find(ProjectFactService.SOURCE_FILE_UPLOAD, "file:11")).thenReturn(fact);

        byte[] bytes = "会议纪要正文".getBytes(StandardCharsets.UTF_8);
        AiFileService.StoredFile stored = service.store(7L, 1L, "纪要.md",
                new ByteArrayInputStream(bytes), "text/markdown", bytes.length, "UPLOAD", null);

        assertThat(stored.deduped()).isFalse();
        assertThat(stored.factId()).isEqualTo(99L);
        assertThat(stored.file().getFilename()).isEqualTo("纪要.md");
        assertThat(stored.file().getSha256()).hasSize(64);
        // 键只由 projectId + sha 决定，原文真的落到对象存储
        String key = stored.file().getObjectKey();
        assertThat(key).startsWith("1/").endsWith(".md");
        assertThat(Files.readAllBytes(tempDir.resolve(key))).isEqualTo(bytes);
    }

    @Test
    @DisplayName("重复投递同一内容：不重复插行、不重复存原文，只是事实刷新")
    void duplicateUploadReusesFile() throws Exception {
        when(accessGuard.canWrite(7L, 1L)).thenReturn(true);
        when(accessGuard.canWrite(8L, 1L)).thenReturn(true);
        insertAssignsId(11L);
        AiFile existing = new AiFile();
        existing.setId(11L);
        existing.setProjectId(1L);
        existing.setSha256("a".repeat(64));
        existing.setObjectKey("1/aa/" + "a".repeat(64) + ".md");
        existing.setFilename("纪要.md");
        existing.setSizeBytes(18L);
        existing.setSource("UPLOAD");
        existing.setVisibility(ProjectFactService.VISIBILITY_PROJECT);
        when(fileMapper.selectOne(any())).thenReturn(null, existing);
        when(factService.upsertFileFact(anyLong(), anyLong(), anyLong(), anyString(), anyString(),
                any(), anyString())).thenReturn(1);

        byte[] bytes = "第二份不同内容".getBytes(StandardCharsets.UTF_8);
        service.store(7L, 1L, "纪要.md", new ByteArrayInputStream(bytes), "text/markdown",
                bytes.length, "UPLOAD", null);
        // 第二次：同项目同 sha 命中已有行
        AiFileService.StoredFile second = service.store(8L, 1L, "纪要.md",
                new ByteArrayInputStream(bytes), "text/markdown", bytes.length, "EMAIL", "msg-1");

        assertThat(second.deduped()).isTrue();
        assertThat(second.file().getId()).isEqualTo(11L);
        // 只插入过一次；第二次只 update
        verify(fileMapper).insert(any(AiFile.class));
        verify(fileMapper).updateById(any(AiFile.class));
    }

    @Test
    @DisplayName("没有项目写权限：直接拒绝，不落原文、不写库、不写事实")
    void rejectWithoutWritePermission() throws Exception {
        when(accessGuard.canWrite(7L, 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.store(7L, 1L, "x.txt",
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                "text/plain", 1, "UPLOAD", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权");

        verify(fileMapper, never()).insert(any(AiFile.class));
        verify(factService, never()).upsertFileFact(anyLong(), anyLong(), anyLong(), anyString(),
                anyString(), any(), anyString());
        assertThat(Files.walk(tempDir).filter(Files::isRegularFile).toList()).isEmpty();
    }

    @Test
    @DisplayName("超过配置上限：拒绝，且不会写入对象存储")
    void rejectOversizeFile() {
        when(accessGuard.canWrite(7L, 1L)).thenReturn(true);
        long tooBig = 6L * 1024 * 1024;

        assertThatThrownBy(() -> service.store(7L, 1L, "big.bin",
                new ByteArrayInputStream(new byte[16]), "application/octet-stream",
                tooBig, "UPLOAD", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上限");

        verify(fileMapper, never()).insert(any(AiFile.class));
    }

    @Test
    @DisplayName("列表与下载都要先过读权限")
    void listAndDownloadRequireReadPermission() {
        when(accessGuard.canRead(7L, 1L)).thenReturn(false);
        assertThatThrownBy(() -> service.list(7L, 1L, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权");

        AiFile file = new AiFile();
        file.setId(5L);
        file.setProjectId(1L);
        when(fileMapper.selectById(5L)).thenReturn(file);
        assertThatThrownBy(() -> service.download(7L, 5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权");
    }

    /** MyBatis-Plus 的 insert 不会回填 id（真实库里由自增提供），测试里手动赋值。 */
    private void insertAssignsId(long id) {
        doAnswer(invocation -> {
            AiFile row = invocation.getArgument(0);
            row.setId(id);
            return 1;
        }).when(fileMapper).insert(any(AiFile.class));
    }
}
