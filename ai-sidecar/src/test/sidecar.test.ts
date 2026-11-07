import assert from "node:assert/strict";
import { after, describe, it } from "node:test";
import { createServer, request, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { createSidecarServer } from "../server.js";
import type { AgentEvent, AgentMessage } from "@earendil-works/pi-agent-core";
import type { AgentFactory, AgentFactoryInput, AgentLike } from "../run.js";
import {
  FALLBACK_MAX_TOKENS,
  MAX_CONTEXT_CHARS,
  PRUNE_KEEP_TAIL,
  buildPiAgent,
  catalogModelFor,
  pruneMessages,
  resolveModelDefinition,
} from "../run.js";
import { callRemoteTool, buildAgentTools } from "../tools.js";

/** Minimal scripted agent: replays canned events, then returns. */
class FakeAgent implements AgentLike {
  readonly state: { errorMessage?: string } = {};
  private listeners: Array<(event: AgentEvent, signal: AbortSignal) => void | Promise<void>> = [];
  private aborted = false;
  private readonly events: AgentEvent[];

  constructor(events: AgentEvent[]) {
    this.events = events;
  }

  subscribe(listener: (event: AgentEvent, signal: AbortSignal) => void | Promise<void>): () => void {
    this.listeners.push(listener);
    return () => {
      this.listeners = this.listeners.filter((l) => l !== listener);
    };
  }

  private async emit(event: AgentEvent): Promise<void> {
    for (const listener of this.listeners) {
      await listener(event, new AbortController().signal);
    }
  }

  async continue(): Promise<void> {
    for (const event of this.events) {
      if (this.aborted) return;
      await this.emit(event);
    }
  }

  abort(): void {
    this.aborted = true;
  }
}

function scriptedFactory(events: AgentEvent[]): (input: AgentFactoryInput) => AgentLike {
  return (_input: AgentFactoryInput) => new FakeAgent(events);
}

function validBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    runId: "run-1",
    provider: "zhipu",
    baseUrl: "https://open.bigmodel.cn/api/paas/v4",
    apiKey: "sk-test",
    model: "glm-5.3",
    systemPrompt: "You are helpful.",
    messages: [{ role: "user", content: "hi", timestamp: 1 }],
    tools: [],
    ...overrides,
  };
}

interface HttpResponse {
  status: number | undefined;
  body: string;
  headers: IncomingMessage["headers"];
}

function post(server: Server, path: string, body: unknown, headers: Record<string, string> = {}): Promise<HttpResponse> {
  const address = server.address();
  if (address === null || typeof address === "string") {
    return Promise.reject(new Error("server not listening on a port"));
  }
  const { promise, resolve, reject } = Promise.withResolvers<HttpResponse>();
  const req = request(
    { host: "127.0.0.1", port: address.port, path, method: "POST", headers, agent: false },
    (res: IncomingMessage) => {
      const chunks: Buffer[] = [];
      res.on("data", (c: Buffer) => chunks.push(c));
      res.on("end", () =>
        resolve({
          status: res.statusCode,
          body: Buffer.concat(chunks).toString("utf8"),
          headers: res.headers,
        }),
      );
    },
  );
  req.on("error", reject);
  req.end(typeof body === "string" ? body : JSON.stringify(body));
  return promise;
}

function listen(server: Server): Promise<number> {
  const { promise, resolve } = Promise.withResolvers<number>();
  server.listen(0, "127.0.0.1", () => {
    const address = server.address();
    if (address === null || typeof address === "string") throw new Error("no port");
    resolve(address.port);
  });
  return promise;
}

function portOf(server: Server): number {
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("server not listening on a port");
  return address.port;
}

function parseSseFrames(raw: string): Array<{ event: string; data: unknown }> {
  const frames: Array<{ event: string; data: unknown }> = [];
  for (const block of raw.split("\n\n")) {
    const lines = block.split("\n").filter((l) => l.length > 0);
    if (lines.length === 0) continue;
    if (lines.every((l) => l.startsWith(":"))) continue; // heartbeat comment
    const event = lines.find((l) => l.startsWith("event: "))?.slice("event: ".length) ?? "";
    const dataLine = lines.find((l) => l.startsWith("data: "))?.slice("data: ".length);
    frames.push({ event, data: dataLine === undefined ? undefined : JSON.parse(dataLine) });
  }
  return frames;
}

function stubToolServer(handler: (body: Record<string, unknown>, res: ServerResponse) => void): Promise<Server> {
  const stub = createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on("data", (c: Buffer) => chunks.push(c));
    req.on("end", () => {
      handler(JSON.parse(Buffer.concat(chunks).toString("utf8")), res);
    });
  });
  return new Promise((resolve) => {
    stub.listen(0, "127.0.0.1", () => resolve(stub));
  });
}

/* ------------------------------------------------------------------ */

describe("POST /v1/runs auth", () => {
  it("returns 401 JSON when X-Sidecar-Token mismatches SIDECAR_TOKEN", async () => {
    const server = createSidecarServer({ token: "secret" });
    await listen(server);
    const res = await post(server, "/v1/runs", validBody(), { "Content-Type": "application/json" });
    assert.equal(res.status, 401);
    const parsed = JSON.parse(res.body) as { error: { code: string } };
    assert.equal(parsed.error.code, "unauthorized");
    server.close();
  });

  it("returns 401 when the header is absent", async () => {
    const server = createSidecarServer({ token: "secret" });
    await listen(server);
    const res = await post(server, "/v1/runs", validBody(), { "Content-Type": "application/json" });
    assert.equal(res.status, 401);
    server.close();
  });

  it("skips the token check when SIDECAR_TOKEN is unset", async () => {
    const server = createSidecarServer({
      createAgent: scriptedFactory([{ type: "agent_end", messages: [] }]),
    });
    await listen(server);
    const res = await post(server, "/v1/runs", validBody(), { "Content-Type": "application/json" });
    assert.equal(res.status, 200);
    server.close();
  });
});

describe("POST /v1/runs validation", () => {
  const cases: Array<[string, Record<string, unknown>]> = [
    ["missing runId", { runId: undefined }],
    ["missing provider", { provider: undefined }],
    ["missing apiKey", { apiKey: undefined }],
    ["missing model", { model: undefined }],
    ["missing systemPrompt", { systemPrompt: undefined }],
    ["messages not array", { messages: "nope" }],
    ["messages too long", { messages: Array.from({ length: 201 }, () => ({ role: "user", content: "x" })) }],
    ["tools not array", { tools: {} }],
  ];

  for (const [name, override] of cases) {
    it(`400 on ${name}`, async () => {
      const server = createSidecarServer();
      await listen(server);
      const res = await post(server, "/v1/runs", validBody(override), { "Content-Type": "application/json" });
      assert.equal(res.status, 400);
      const parsed = JSON.parse(res.body) as { error: { code: string; message: string } };
      assert.equal(parsed.error.code, "invalid_request");
      assert.equal(typeof parsed.error.message, "string");
      server.close();
    });
  }

  it("accepts openai-compatible relay provider and streams a run", async () => {
    const events: AgentEvent[] = [
      { type: "agent_start" } as AgentEvent,
      { type: "agent_end", messages: [] } as AgentEvent,
    ];
    const seen: Array<Record<string, unknown>> = [];
    const factory: AgentFactory = (input) => {
      seen.push({ provider: input.provider, baseUrl: input.baseUrl, model: input.model });
      return new FakeAgent(events);
    };
    const server = createSidecarServer({ createAgent: factory });
    await listen(server);
    const res = await post(
      server,
      "/v1/runs",
      validBody({
        provider: "openai",
        model: "gpt-4o-mini",
        baseUrl: "https://relay.example.com/v1",
      }),
      { "Content-Type": "application/json" },
    );
    assert.equal(res.status, 200);
    const frames = parseSseFrames(res.body);
    assert.equal(frames[frames.length - 1].event, "run_end");
    assert.deepEqual(seen[0], {
      provider: "openai",
      baseUrl: "https://relay.example.com/v1",
      model: "gpt-4o-mini",
    });
    server.close();
  });

  it("400 when custom provider has empty baseUrl", async () => {
    const server = createSidecarServer();
    await listen(server);
    const res = await post(
      server,
      "/v1/runs",
      validBody({ provider: "openai", baseUrl: "" }),
      { "Content-Type": "application/json" },
    );
    assert.equal(res.status, 400);
    const parsed = JSON.parse(res.body) as { error: { message: string } };
    assert.match(parsed.error.message, /baseUrl/);
    server.close();
  });

  it("accepts deepseek provider and streams a run", async () => {
    const events: AgentEvent[] = [
      { type: "agent_start" } as AgentEvent,
      {
        type: "message_end",
        message: {
          role: "assistant",
          content: [{ type: "text", text: "hello from deepseek" }],
          timestamp: 1,
        },
      } as AgentEvent,
      { type: "agent_end", messages: [] } as AgentEvent,
    ];
    const seen: Array<Record<string, unknown>> = [];
    const factory: AgentFactory = (input) => {
      seen.push({ provider: input.provider, baseUrl: input.baseUrl, model: input.model });
      return new FakeAgent(events);
    };
    const server = createSidecarServer({ createAgent: factory });
    await listen(server);
    const res = await post(
      server,
      "/v1/runs",
      validBody({ provider: "deepseek", model: "deepseek-v4-flash", baseUrl: "https://api.deepseek.com" }),
      { "Content-Type": "application/json" },
    );
    assert.equal(res.status, 200);
    const frames = parseSseFrames(res.body);
    assert.equal(frames[frames.length - 1].event, "run_end");
    assert.deepEqual(seen[0], {
      provider: "deepseek",
      baseUrl: "https://api.deepseek.com",
      model: "deepseek-v4-flash",
    });
    server.close();
  });
});

describe("tool callback client", () => {
  it("POSTs runId/toolName/args with token + content-type headers", async () => {
    let seen: { headers: IncomingMessage["headers"]; body: string } | undefined;
    const stub = createServer((req, res) => {
      const chunks: Buffer[] = [];
      req.on("data", (c: Buffer) => chunks.push(c));
      req.on("end", () => {
        seen = { headers: req.headers, body: Buffer.concat(chunks).toString("utf8") };
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ content: "done", isError: false }));
      });
    });
    await listen(stub);

    const result = await callRemoteTool({
      runId: "run-9",
      toolName: "query_my_tasks",
      args: { status: "open" },
      toolCallbackUrl: `http://127.0.0.1:${portOf(stub)}/internal/ai-agent/tools`,
      token: "tok-1",
    });

    assert.deepEqual(result, { content: "done", isError: false });
    assert.equal(seen?.headers["content-type"], "application/json");
    assert.equal(seen?.headers["x-sidecar-token"], "tok-1");
    assert.deepEqual(JSON.parse(seen?.body ?? "{}") satisfies Record<string, unknown>, {
      runId: "run-9",
      toolName: "query_my_tasks",
      args: { status: "open" },
    });
    stub.close();
  });
  it("propagates isError:true bodies as errors", async () => {
    const stub = await stubToolServer((_body, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ content: "boom", isError: true }));
    });
    await assert.rejects(
      callRemoteTool({
        runId: "r",
        toolName: "t",
        args: {},
        toolCallbackUrl: `http://127.0.0.1:${portOf(stub)}/x`,
      }),
      /boom/,
    );
    stub.close();
  });

  it("treats non-2xx as failure with status in the message", async () => {
    const stub = await stubToolServer((_body, res) => {
      res.writeHead(503);
      res.end("unavailable");
    });
    await assert.rejects(
      callRemoteTool({
        runId: "r",
        toolName: "t",
        args: {},
        toolCallbackUrl: `http://127.0.0.1:${portOf(stub)}/x`,
      }),
      /HTTP 503/,
    );
    stub.close();
  });
});

describe("SSE event relay", () => {
  it("emits contract frames for a scripted agent loop", async () => {
    const assistantMessage: AgentMessage = {
      role: "assistant",
      content: [{ type: "text", text: "Hello!" }],
      api: "openai-completions",
      provider: "zhipu",
      model: "glm-5.3",
      usage: {
        input: 1, output: 1, cacheRead: 0, cacheWrite: 0,
        totalTokens: 2, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
      },
      stopReason: "stop",
      timestamp: 2,
    };
    const events: AgentEvent[] = [
      { type: "agent_start" },
      { type: "message_start", message: assistantMessage },
      { type: "message_update", message: assistantMessage, assistantMessageEvent: { type: "thinking_delta", delta: "hmm", contentIndex: 0, partial: assistantMessage } },
      { type: "message_update", message: assistantMessage, assistantMessageEvent: { type: "text_delta", delta: "Hello!", contentIndex: 0, partial: assistantMessage } },
      { type: "message_end", message: assistantMessage },
      { type: "tool_execution_start", toolCallId: "tc-1", toolName: "query_my_tasks", args: { status: "open" } },
      { type: "tool_execution_end", toolCallId: "tc-1", toolName: "query_my_tasks", result: { content: [{ type: "text", text: "3 tasks" }], details: {} }, isError: false },
      { type: "agent_end", messages: [assistantMessage] },
    ];
    const server = createSidecarServer({ createAgent: scriptedFactory(events) });
    await listen(server);
    const res = await post(server, "/v1/runs", validBody(), { "Content-Type": "application/json" });

    assert.equal(res.status, 200);
    assert.match(String(res.headers["content-type"]), /text\/event-stream/);
    const frames = parseSseFrames(res.body);
    const names = frames.map((f) => f.event);
    assert.deepEqual(names, [
      "run_start",
      "message_start",
      "message_delta",
      "message_delta",
      "message_end",
      "tool_execution_start",
      "tool_execution_end",
      "run_end",
    ]);
    assert.deepEqual(frames[0].data, { runId: "run-1" });
    assert.deepEqual(frames[1].data, { index: 0 });
    assert.deepEqual(frames[2].data, { index: 0, delta: { type: "thinking_delta", text: "hmm" } });
    assert.deepEqual(frames[3].data, { index: 0, delta: { type: "text_delta", text: "Hello!" } });
    assert.equal((frames[4].data as { message: { role: string } }).message.role, "assistant");
    assert.deepEqual(frames[5].data, { toolCallId: "tc-1", toolName: "query_my_tasks", args: { status: "open" } });
    assert.equal((frames[6].data as { toolCallId: string }).toolCallId, "tc-1");
    assert.equal((frames[6].data as { isError: boolean }).isError, false);
    assert.equal((frames[6].data as { result: string }).result, "3 tasks");
    assert.deepEqual((frames[7].data as { newMessages: unknown[] }).newMessages, [assistantMessage]);
    server.close();
  });

  it("emits an error frame and closes on provider failure", async () => {
    const failingAgent: AgentLike = {
      subscribe: () => () => undefined,
      continue: async () => undefined,
      abort: () => undefined,
      get state() {
        return { errorMessage: "provider exploded" };
      },
    };
    const server = createSidecarServer({ createAgent: () => failingAgent });
    await listen(server);
    const res = await post(server, "/v1/runs", validBody(), { "Content-Type": "application/json" });
    const frames = parseSseFrames(res.body);
    const last = frames[frames.length - 1];
    assert.equal(last.event, "error");
    assert.equal((last.data as { code: string }).code, "provider_error");
    server.close();
  });
});

describe("buildAgentTools", () => {
  it("delegates execution to the callback URL and throws on isError", async () => {
    const stub = await stubToolServer((body, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      if (body.toolName === "ok_tool") {
        res.end(JSON.stringify({ content: "fine", isError: false }));
      } else {
        res.end(JSON.stringify({ content: "bad tool", isError: true }));
      }
    });
    const tools = buildAgentTools({
      tools: [{ name: "ok_tool", description: "d", parameters: { type: "object", properties: {} } }],
      runId: "run-x",
      toolCallbackUrl: `http://127.0.0.1:${portOf(stub)}/cb`,
      token: "tk",
    });
    assert.equal(tools.length, 1);
    assert.equal(tools[0].name, "ok_tool");
    const ok = await tools[0].execute("call-1", {});
    assert.deepEqual(ok.content, [{ type: "text", text: "fine" }]);

    const failing = buildAgentTools({
      tools: [{ name: "bad_tool", description: "d", parameters: { type: "object", properties: {} } }],
      runId: "run-x",
      toolCallbackUrl: `http://127.0.0.1:${portOf(stub)}/cb`,
    });
    await assert.rejects(failing[0].execute("call-2", {}), /bad tool/);
    stub.close();
  });
});

describe("pruneMessages", () => {
  function systemMessage(text: string): AgentMessage {
    return { role: "system", content: text, timestamp: 0 } as unknown as AgentMessage;
  }

  function turnMessage(role: "user" | "assistant", text: string, timestamp: number): AgentMessage {
    return role === "user"
      ? ({ role, content: [{ type: "text", text }], timestamp } as unknown as AgentMessage)
      : ({
          role,
          content: [{ type: "text", text }],
          api: "openai-completions",
          provider: "deepseek",
          model: "deepseek-flash",
          usage: {
            input: 1,
            output: 1,
            cacheRead: 0,
            cacheWrite: 0,
            totalTokens: 2,
            cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
          },
          stopReason: "stop",
          timestamp,
        } as unknown as AgentMessage);
  }

  it("keeps system messages and the newest tail, dropping the oldest turns", () => {
    const system = systemMessage("system prompt");
    const messages: AgentMessage[] = [system];
    for (let i = 0; i < 100; i += 1) {
      messages.push(turnMessage(i % 2 === 0 ? "user" : "assistant", "x".repeat(1000), i + 1));
    }

    const pruned = pruneMessages(messages, 20_000);
    assert.ok(pruned.length < messages.length, "expected the transcript to be shortened");
    assert.equal(pruned[0], system, "system message must survive pruning");
    assert.deepEqual(pruned.slice(-PRUNE_KEEP_TAIL), messages.slice(-PRUNE_KEEP_TAIL));
    assert.ok(!pruned.includes(messages[1]), "the oldest turn must be dropped first");
    // Already fits now, so a second pass is a no-op.
    assert.equal(pruneMessages(pruned, 20_000), pruned);
  });

  it("leaves a transcript untouched when it fits or holds nothing but the tail", () => {
    const messages: AgentMessage[] = [
      systemMessage("sys"),
      turnMessage("user", "hi", 1),
      turnMessage("assistant", "hello", 2),
    ];
    assert.equal(pruneMessages(messages), messages); // default budget: unchanged
    assert.deepEqual(pruneMessages(messages, 1), messages); // nothing prunable outside the tail
    assert.ok(MAX_CONTEXT_CHARS > 0);
  });
});

describe("model definition resolution", () => {
  it("uses catalog cost/capabilities and only falls back for unknown models", () => {
    const catalogModel = catalogModelFor("deepseek", "deepseek-flash");
    const resolved = resolveModelDefinition("deepseek", "https://api.deepseek.com", "deepseek-flash");

    if (catalogModel) {
      assert.deepEqual(resolved.cost, catalogModel.cost);
      assert.ok(
        resolved.cost.input > 0 || resolved.cost.output > 0,
        `catalog cost must not be all zero: ${JSON.stringify(resolved.cost)}`,
      );
      assert.equal(resolved.contextWindow, catalogModel.contextWindow);
      assert.equal(resolved.maxTokens, catalogModel.maxTokens);
      assert.deepEqual(resolved.input, catalogModel.input);
      assert.ok(resolved.maxTokens > 8192, "catalog maxTokens must beat the old 8k cap");
      assert.ok(resolved.contextWindow > 200_000, "catalog contextWindow must beat the old 200k cap");
    } else {
      // No catalog data available in this environment: conservative fallback.
      assert.deepEqual(resolved.cost, { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 });
      assert.deepEqual(resolved.input, ["text"]);
      assert.equal(resolved.maxTokens, FALLBACK_MAX_TOKENS);
    }

    // Unknown model ids always take the fallback path, never invented prices.
    const unknown = resolveModelDefinition("deepseek", "https://api.deepseek.com", "not-a-catalog-model");
    assert.deepEqual(unknown.cost, { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 });
    assert.deepEqual(unknown.input, ["text"]);
    assert.equal(unknown.maxTokens, FALLBACK_MAX_TOKENS);
  });
});

describe("sessionId", () => {
  it("reaches the Agent constructor and the agent factory", async () => {
    const agent = buildPiAgent({
      runId: "run-1",
      provider: "deepseek",
      baseUrl: "https://api.deepseek.com",
      apiKey: "sk-test",
      model: "deepseek-flash",
      systemPrompt: "You are helpful.",
      messages: [{ role: "user", content: "hi", timestamp: 1 }],
      tools: [],
      sessionId: "sess-42",
    }) as unknown as { sessionId?: string };
    assert.equal(agent.sessionId, "sess-42");

    const withoutSession = buildPiAgent({
      runId: "run-2",
      provider: "deepseek",
      baseUrl: "https://api.deepseek.com",
      apiKey: "sk-test",
      model: "deepseek-flash",
      systemPrompt: "You are helpful.",
      messages: [{ role: "user", content: "hi", timestamp: 1 }],
      tools: [],
    }) as unknown as { sessionId?: string };
    assert.equal(withoutSession.sessionId, undefined);

    // And the HTTP body value survives validation into the factory input.
    let seen: AgentFactoryInput | undefined;
    const server = createSidecarServer({
      createAgent: (input: AgentFactoryInput) => {
        seen = input;
        return new FakeAgent([]);
      },
    });
    await listen(server);
    const res = await post(server, "/v1/runs", validBody({ sessionId: "sess-http" }), {
      "Content-Type": "application/json",
    });
    assert.equal(res.status, 200);
    assert.equal(seen?.sessionId, "sess-http");
    server.close();
  });
});

after(() => {
  // each test closes its own server
});
