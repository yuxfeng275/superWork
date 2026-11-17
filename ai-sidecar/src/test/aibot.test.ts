/**
 * 企微智能机器人长连接桥的单元 smoke：
 * 用假 WS client + 假 fetch 走一遍「入站文本 → 后端落库 → 回话」，
 * 不连企微、不产生任何真实群发。
 */
import test from "node:test";
import assert from "node:assert/strict";
import {
  fetchCredentials,
  markSeen,
  matchedReplyText,
  onFrame,
  readParsedFields,
  type WsFrame,
} from "../aibot.js";

/** 假 client：只记录 replyStream 调用，不建连接 */
function fakeClient() {
  const replies: Array<{ streamId: string; content: string; finish: boolean }> = [];
  return {
    replies,
    client: {
      connect() {},
      disconnect() {},
      on() {},
      reply() {},
      replyStream(_frame: WsFrame, streamId: string, content: string, finish: boolean) {
        replies.push({ streamId, content, finish });
      },
    },
  };
}

function frame(msgid: string, text: string, userId = "wx-user-1"): WsFrame {
  return { cmd: "message", body: { msgid, msgtype: "text", text: { content: text }, from: { userid: userId } } };
}

const originalFetch = globalThis.fetch;

test("fetchCredentials：后端非 200 时返回 null，不抛错", async () => {
  globalThis.fetch = (async () => new Response("nope", { status: 500 })) as typeof fetch;
  try {
    assert.equal(await fetchCredentials(), null);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("readParsedFields：只取 probability / nextAt，坏 JSON 返回 null", () => {
  assert.deepEqual(readParsedFields({ data: { parsedJson: '{"probability":60,"nextAt":"2026-12-01T15:00:00"}' } }),
    { probability: 60, nextAt: "2026-12-01T15:00:00" });
  assert.equal(readParsedFields({ data: { parsedJson: "not-json" } }), null);
  assert.equal(readParsedFields({ data: {} }), null);
});

test("matchedReplyText：有解析值带回话，没有则提示去商机详情补", () => {
  assert.equal(matchedReplyText({ probability: 60, nextAt: "2026-12-01T15:00:00" }),
    "已写入合拍：成交概率 60%，下次跟进 2026-12-01 15:00");
  assert.match(matchedReplyText(null), /未识别到成交概率/);
});

test("markSeen：同一 msgid 第二次返回 false", () => {
  assert.equal(markSeen("m-1"), true);
  assert.equal(markSeen("m-1"), false);
});

test("onFrame：入站文本 → 后端成功 → 回一句话（不连企微）", async () => {
  const { client, replies } = fakeClient();
  const calls: string[] = [];
  globalThis.fetch = (async (input: Parameters<typeof fetch>[0]) => {
    calls.push(String(input));
    return new Response(JSON.stringify({ data: { parsedJson: '{"probability":60}' } }),
      { status: 200, headers: { "Content-Type": "application/json" } });
  }) as typeof fetch;
  try {
    onFrame(client as never, frame("m-2", "客户说下周给答复"));
    await new Promise((resolve) => setTimeout(resolve, 20));
    assert.equal(calls.length, 1);
    assert.match(calls[0], /\/internal\/opportunity\/reply$/);
    assert.ok(replies.some((reply) => reply.content.includes("已写入合拍")));
    // 回话走 stream 两帧（首帧 + finish）
    assert.ok(replies.some((reply) => reply.finish));
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("onFrame：重复 msgid 只转发一次", async () => {
  const { client } = fakeClient();
  let calls = 0;
  globalThis.fetch = (async () => {
    calls += 1;
    return new Response(JSON.stringify({ data: null }), { status: 200 });
  }) as typeof fetch;
  try {
    onFrame(client as never, frame("m-3", "第一次"));
    onFrame(client as never, frame("m-3", "重复投递"));
    await new Promise((resolve) => setTimeout(resolve, 20));
    assert.equal(calls, 1);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
