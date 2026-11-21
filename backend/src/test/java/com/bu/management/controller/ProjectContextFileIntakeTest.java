package com.bu.management.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.config.ProjectContextProperties;
import com.bu.management.entity.AiFile;
import com.bu.management.service.AiFileService;
import com.bu.management.service.AutomationRunService;
import com.bu.management.service.ProjectBriefPushService;
import com.bu.management.service.ProjectBriefService;
import com.bu.management.service.ProjectFactIntakeService;
import com.bu.management.service.ProjectFactService;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 内部投递通道的契约：邮件转发与目录同步都走同一个入口，token 不对直接 401，
 * 来源原样落到 {@link AiFileService}（由服务层判定项目写权限）。
 */
@ExtendWith(MockitoExtension.class)
class ProjectContextFileIntakeTest {

    @Mock private ProjectFactService factService;
    @Mock private ProjectFactIntakeService intakeService;
    @Mock private ProjectBriefService briefService;
    @Mock private ProjectBriefPushService briefPushService;
    @Mock private AutomationRunService automationRunService;
    @Mock private AiFileService aiFileService;

    private ProjectContextInternalController controller;

    private ProjectContextProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ProjectContextProperties();
        properties.setToken("s3cret");
        controller = new ProjectContextInternalController(factService, intakeService, briefService,
                briefPushService, automationRunService, properties, aiFileService);
    }

    @Test
    @DisplayName("邮件转发：source=EMAIL 与邮件 id 一起传给文件服务")
    void emailForwardUsesEmailSource() throws Exception {
        AiFile file = row(11L);
        when(aiFileService.store(eq(9L), eq(1L), eq("客户反馈.xlsx"), any(InputStream.class),
                anyString(), anyLong(), eq("EMAIL"), eq("msg-42")))
                .thenReturn(new AiFileService.StoredFile(file, 77L, false));

        ResponseEntity<?> response = controller.uploadFile(9L, 1L, multipart("客户反馈.xlsx"),
                "EMAIL", "msg-42", token("s3cret"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(String.valueOf(response.getBody())).contains("77");
    }

    @Test
    @DisplayName("目录同步：source=SYNC_DIR 照旧走同一入口")
    void syncDirUsesSyncSource() throws Exception {
        when(aiFileService.store(eq(9L), eq(1L), eq("日报.md"), any(InputStream.class),
                anyString(), anyLong(), eq("SYNC_DIR"), eq("/home/u/inbox")))
                .thenReturn(new AiFileService.StoredFile(row(12L), 78L, false));

        ResponseEntity<?> response = controller.uploadFile(9L, 1L, multipart("日报.md"),
                "SYNC_DIR", "/home/u/inbox", token("s3cret"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("token 不对：401，且不触达文件服务")
    void wrongTokenRejected() {
        ResponseEntity<?> response = controller.uploadFile(9L, 1L, multipart("x.txt"),
                "EMAIL", null, token("wrong"));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(aiFileService, never()).store(anyLong(), anyLong(), anyString(), any(), anyString(),
                anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("空文件：400，不触达文件服务")
    void emptyFileRejected() {
        ResponseEntity<?> response = controller.uploadFile(9L, 1L,
                new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]),
                "EMAIL", null, token("s3cret"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(aiFileService, never()).store(anyLong(), anyLong(), anyString(), any(), anyString(),
                anyLong(), anyString(), any());
    }

    private static MultipartFile multipart(String name) {
        return new MockMultipartFile("file", name, "text/plain", "内容".getBytes());
    }

    private static MockHttpServletRequest token(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Sidecar-Token", value);
        return request;
    }

    private static AiFile row(Long id) {
        AiFile file = new AiFile();
        file.setId(id);
        file.setProjectId(1L);
        file.setFilename("x");
        file.setSizeBytes(3L);
        return file;
    }
}
