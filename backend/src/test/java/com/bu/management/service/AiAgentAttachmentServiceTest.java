package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.config.AiAgentAttachmentProperties;
import com.bu.management.entity.AiAgentAttachment;
import com.bu.management.entity.AiAgentSession;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.AiAgentAttachmentMapper;
import com.bu.management.mapper.AiAgentSessionMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class AiAgentAttachmentServiceTest {

    @Mock private AiAgentAttachmentMapper attachmentMapper;
    @Mock private AiAgentSessionMapper sessionMapper;
    @Mock private AiAgentAttachmentStorage storage;

    private AiAgentAttachmentService service;

    @BeforeEach
    void setUp() {
        AiAgentAttachmentProperties properties = new AiAgentAttachmentProperties();
        properties.setMaxSizeMb(1);
        service = new AiAgentAttachmentService(attachmentMapper, sessionMapper, storage, properties);
    }

    private AiAgentSession session(Long id, Long ownerId) {
        AiAgentSession session = new AiAgentSession();
        session.setId(id);
        session.setOwnerUserId(ownerId);
        return session;
    }

    @Test
    @DisplayName("上传：归属校验通过后落盘并入库")
    void uploadStoresAndPersists() throws Exception {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, 7L));
        when(storage.store(any(), any(), any()))
                .thenReturn(new AiAgentAttachmentStorage.StoredAttachment("s1/abc.md", 5L, "sha"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.md", "text/markdown", "hello".getBytes());

        var vo = service.upload(7L, 1L, file);

        ArgumentCaptor<AiAgentAttachment> captor = ArgumentCaptor.forClass(AiAgentAttachment.class);
        verify(attachmentMapper).insert(captor.capture());
        AiAgentAttachment saved = captor.getValue();
        assertThat(saved.getSessionId()).isEqualTo(1L);
        assertThat(saved.getOwnerUserId()).isEqualTo(7L);
        assertThat(saved.getFileName()).isEqualTo("notes.md");
        assertThat(saved.getRelativePath()).isEqualTo("s1/abc.md");
        assertThat(vo.fileName()).isEqualTo("notes.md");
        assertThat(vo.sizeBytes()).isEqualTo(5L);
    }

    @Test
    @DisplayName("上传：非归属人抛 ResourceNotFoundException")
    void uploadRejectsNonOwner() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, 7L));
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.md", "text/markdown", "hello".getBytes());

        assertThatThrownBy(() -> service.upload(99L, 1L, file))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("上传：超过大小上限拒绝")
    void uploadRejectsOversize() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, 7L));
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.bin", "application/octet-stream", new byte[2 * 1024 * 1024]);

        assertThatThrownBy(() -> service.upload(7L, 1L, file))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("大小上限");
    }

    @Test
    @DisplayName("上下文注入：文本附件带正文摘要，二进制只带文件名与大小")
    void composeContextIncludesExcerpts() {
        AiAgentAttachment text = attachment(11L, "周报.md", "text/markdown", "s1/a.md", 100L);
        AiAgentAttachment binary = attachment(12L, "截图.png", "image/png", "s1/b.png", 2048L);
        when(attachmentMapper.selectBatchIds(List.of(11L, 12L))).thenReturn(List.of(text, binary));
        when(storage.excerpt("s1/a.md", "周报.md")).thenReturn("本周完成联调");
        when(storage.excerpt("s1/b.png", "截图.png")).thenReturn(null);

        String context = service.composeContext(1L, List.of(11L, 12L));

        assertThat(context).contains("【附件 周报.md】").contains("本周完成联调");
        assertThat(context).contains("【附件 截图.png】").contains("二进制文件").contains("2.0KB");
        assertThat(context).doesNotContain("正文未注入\n\n【附件 周报");
    }

    @Test
    @DisplayName("上下文注入：引用其他会话的附件被拒绝")
    void composeContextRejectsForeignSession() {
        AiAgentAttachment foreign = attachment(13L, "x.md", "text/markdown", "s9/x.md", 10L);
        foreign.setSessionId(9L);
        when(attachmentMapper.selectBatchIds(List.of(13L))).thenReturn(List.of(foreign));

        assertThatThrownBy(() -> service.composeContext(1L, List.of(13L)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("不属于当前会话");
    }

    @Test
    @DisplayName("展示标记：[附件: 文件名] 按序拼接")
    void displayMarkers() {
        AiAgentAttachment a = attachment(11L, "a.txt", "text/plain", "s1/a.txt", 1L);
        AiAgentAttachment b = attachment(12L, "b.png", "image/png", "s1/b.png", 2L);
        when(attachmentMapper.selectBatchIds(List.of(11L, 12L))).thenReturn(List.of(a, b));

        assertThat(service.displayMarkers(1L, List.of(11L, 12L)))
                .isEqualTo("[附件: a.txt] [附件: b.png]");
    }

    @Test
    @DisplayName("列表：仅返回本会话附件，按 id 升序")
    void listScopedToSession() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, 7L));
        when(attachmentMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(attachment(11L, "a.txt", "text/plain", "s1/a.txt", 1L)));

        var list = service.list(7L, 1L);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).fileName()).isEqualTo("a.txt");
    }

    private AiAgentAttachment attachment(Long id, String name, String type, String path, long size) {
        AiAgentAttachment attachment = new AiAgentAttachment();
        attachment.setId(id);
        attachment.setSessionId(1L);
        attachment.setOwnerUserId(7L);
        attachment.setFileName(name);
        attachment.setContentType(type);
        attachment.setRelativePath(path);
        attachment.setSizeBytes(size);
        attachment.setSha256("sha");
        return attachment;
    }
}
