package com.bu.management.integration;

import com.bu.management.config.YunxiaoRuntimeConfig;
import com.bu.management.service.YunxiaoConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YunxiaoClientTest {

    private HttpServer server;
    private YunxiaoClient client;
    private final AtomicInteger estimatedEffortAttempts = new AtomicInteger();
    private final AtomicInteger projectSearchRequests = new AtomicInteger();
    private final AtomicInteger memberSearchRequests = new AtomicInteger();
    private final AtomicReference<JsonNode> workitemSearchBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/oapi/v1/projex/workitems:search", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            workitemSearchBody.set(new ObjectMapper().readTree(requestBody));
            respond(exchange, "[{\"id\":\"work-1\",\"subject\":\"测试需求\"}]");
        });
        server.createContext("/oapi/v1/projex/projects:search", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode body = new ObjectMapper().readTree(requestBody);
            int page = body.path("page").asInt();
            assertThat(body.path("perPage").asInt()).isEqualTo(200);
            projectSearchRequests.incrementAndGet();
            if (page == 1) {
                StringBuilder projects = new StringBuilder("[");
                for (int index = 1; index <= 200; index++) {
                    if (index > 1) projects.append(',');
                    projects.append("{\"id\":\"project-").append(index)
                            .append("\",\"name\":\"项目").append(index).append("\"}");
                }
                projects.append(']');
                respond(exchange, projects.toString());
            } else {
                respond(exchange, "[{\"id\":\"project-201\",\"name\":\"皇家 omniCRM\","
                        + "\"customCode\":\"ROYAL\",\"status\":\"NORMAL\"}]");
            }
        });
        server.createContext("/oapi/v1/projex/workitems/work-1/effortRecords", exchange -> respond(exchange,
                "[{\"id\":\"effort-1\",\"actualTime\":7.5}]"));
        server.createContext("/oapi/v1/projex/workitems/work-1/estimatedEfforts", exchange -> {
            if (estimatedEffortAttempts.incrementAndGet() < 3) {
                respond(exchange, 429, "{\"message\":\"rate limited\"}");
            } else {
                respond(exchange, 200, "[{\"id\":\"estimate-1\",\"spentTime\":8}]");
            }
        });
        server.createContext("/oapi/v1/platform/user", exchange -> respond(exchange,
                "{\"id\":\"user-1\",\"name\":\"测试用户\",\"email\":\"test@example.com\"}"));
        server.createContext("/oapi/v1/platform/members:search", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode body = new ObjectMapper().readTree(requestBody);
            int page = body.path("page").asInt();
            assertThat(body.path("perPage").asInt()).isEqualTo(100);
            assertThat(body.path("statuses").get(0).asText()).isEqualTo("ENABLED");
            memberSearchRequests.incrementAndGet();
            if (page == 1) {
                StringBuilder members = new StringBuilder("[");
                for (int index = 1; index <= 100; index++) {
                    if (index > 1) members.append(',');
                    members.append("{\"id\":\"member-").append(index)
                            .append("\",\"userId\":\"user-").append(index)
                            .append("\",\"name\":\"成员").append(index).append("\"}");
                }
                members.append(']');
                respond(exchange, members.toString());
            } else {
                respond(exchange, "[{\"id\":\"member-101\",\"userId\":\"user-yufeng\","
                        + "\"name\":\"于峰\",\"email\":\"yufeng@example.com\",\"status\":\"ENABLED\"}]");
            }
        });
        server.start();

        YunxiaoConfigService configService = mock(YunxiaoConfigService.class);
        when(configService.getRuntimeConfig()).thenReturn(new YunxiaoRuntimeConfig(
                true,
                "region",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                null,
                "test-token",
                "PAGE",
                null,
                null,
                null
        ));
        client = new YunxiaoClient(configService, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void searchesWorkitemsAndReadsEffortRecordsUsingRegionEndpoint() {
        List<JsonNode> workitems = client.searchWorkitems("project-1", "Req");
        List<JsonNode> efforts = client.listEffortRecords("work-1");

        assertThat(workitems).hasSize(1);
        assertThat(workitems.get(0).path("id").asText()).isEqualTo("work-1");
        assertThat(efforts.get(0).path("actualTime").decimalValue()).isEqualByComparingTo("7.5");
    }

    @Test
    void searchesAllExecutionCategoriesModifiedWithinLookbackWindow() throws Exception {
        client.searchWorkitems("project-1", "Req,Task,Bug", LocalDate.of(2026, 7, 5));

        JsonNode body = workitemSearchBody.get();
        assertThat(body.path("category").asText()).isEqualTo("Req,Task,Bug");
        JsonNode conditions = new ObjectMapper().readTree(body.path("conditions").asText());
        JsonNode modifiedFilter = conditions.path("conditionGroups").get(0).get(0);
        assertThat(modifiedFilter.path("fieldIdentifier").asText()).isEqualTo("gmtModified");
        assertThat(modifiedFilter.path("operator").asText()).isEqualTo("BETWEEN");
        assertThat(modifiedFilter.path("value").get(0).asText()).isEqualTo("2026-07-05 00:00:00");
    }

    @Test
    void retriesRateLimitedRequestsWithBackoff() {
        List<JsonNode> efforts = client.listEstimatedEfforts("work-1");

        assertThat(estimatedEffortAttempts).hasValue(3);
        assertThat(efforts.get(0).path("spentTime").decimalValue()).isEqualByComparingTo("8");
    }

    @Test
    void testsConnectionThroughCurrentUserEndpoint() {
        JsonNode user = client.getCurrentUser();

        assertThat(user.path("id").asText()).isEqualTo("user-1");
        assertThat(user.path("name").asText()).isEqualTo("测试用户");
    }

    @Test
    void searchesAllProjectsUsingSavedConnectionParameters() {
        List<JsonNode> projects = client.searchProjects();

        assertThat(projectSearchRequests).hasValue(2);
        assertThat(projects).hasSize(201);
        assertThat(projects.get(200).path("id").asText()).isEqualTo("project-201");
        assertThat(projects.get(200).path("customCode").asText()).isEqualTo("ROYAL");
    }

    @Test
    void searchesAllEnabledOrganizationMembersUsingUserIds() {
        List<JsonNode> members = client.searchMembers();

        assertThat(memberSearchRequests).hasValue(2);
        assertThat(members).hasSize(101);
        assertThat(members.get(100).path("id").asText()).isEqualTo("member-101");
        assertThat(members.get(100).path("userId").asText()).isEqualTo("user-yufeng");
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, body);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        assertThat(exchange.getRequestHeaders().getFirst("x-yunxiao-token")).isEqualTo("test-token");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
