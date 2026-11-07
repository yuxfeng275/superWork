package com.bu.management.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.bu.management.service.ProjectFactAccessGuard;
import com.bu.management.service.ProjectFactIntakeService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 投递接口的准入拒绝必须是真 HTTP 403，而不是 200 + body.code=403：
 * 前端 requestJson 只在非 2xx 时抛 ApiRequestError，网关/日志统计也按 status 看。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectFactIntakeControllerTest {

    private static final Long PROJECT_ID = 11L;
    private static final Long STRANGER_ID = 7L;

    @Mock private ProjectFactIntakeService intakeService;
    @Mock private ProjectFactAccessGuard accessGuard;

    private ProjectFactIntakeController controller;

    @BeforeEach
    void setUp() {
        controller = new ProjectFactIntakeController(intakeService, accessGuard);
        when(accessGuard.canWrite(anyLong(), anyLong())).thenReturn(false);
        when(accessGuard.canRead(anyLong(), anyLong())).thenReturn(false);
    }

    @Test
    @DisplayName("写准入不通过：upload 返回 403")
    void uploadRejectsWith403() {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
        ResponseEntity<?> response = controller.upload(
                PROJECT_ID, file, null, null, null, STRANGER_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("写准入不通过：note 返回 403")
    void noteRejectsWith403() {
        ResponseEntity<?> response = controller.note(
                PROJECT_ID, new ProjectFactIntakeController.NoteRequest("t", "b", "PROJECT"), STRANGER_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("写准入不通过：chat 返回 403")
    void chatRejectsWith403() {
        ResponseEntity<?> response = controller.chat(
                PROJECT_ID, new ProjectFactIntakeService.ChatRequest(List.of(), "PROJECT"), STRANGER_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("读准入不通过：list 返回 403")
    void listRejectsWith403() {
        ResponseEntity<?> response = controller.list(PROJECT_ID, null, 50, STRANGER_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("参数错误仍是 400，不被 403 吞掉")
    void emptyFileIs400() {
        when(accessGuard.canWrite(anyLong(), anyLong())).thenReturn(true);
        MockMultipartFile empty = new MockMultipartFile("file", "a.txt", "text/plain", new byte[0]);
        ResponseEntity<?> response = controller.upload(
                PROJECT_ID, empty, null, null, null, STRANGER_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
