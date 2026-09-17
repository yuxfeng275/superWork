import { createServer, type Server } from "node:http";
import { pathToFileURL } from "node:url";
import { handleRun, type AgentFactory } from "./run.js";

/**
 * ai-sidecar HTTP entry: minimal node:http server.
 *
 * GET  /healthz    -> 200 {"ok":true,"version":"0.1.0"}
 * POST /v1/runs    -> SSE run stream (see docs/ai-agent-sidecar.md)
 */

export const DEFAULT_PORT = 8787;
export const VERSION = "0.1.0";

export interface SidecarServerOptions {
  /** SIDECAR_TOKEN override (defaults to process.env.SIDECAR_TOKEN). */
  token?: string;
  /** Injectable agent factory (tests). Defaults to the pi Agent factory. */
  createAgent?: AgentFactory;
  maxRunMs?: number;
  heartbeatMs?: number;
}

export function createSidecarServer(options: SidecarServerOptions = {}): Server {
  return createServer((req, res) => {
    const url = new URL(req.url ?? "/", "http://localhost");

    if (req.method === "GET" && url.pathname === "/healthz") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, version: VERSION }));
      return;
    }

    if (req.method === "POST" && url.pathname === "/v1/runs") {
      void handleRun(req, res, options).catch(() => {
        if (!res.writableEnded && !res.destroyed) {
          res.writeHead(500, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ error: { code: "internal_error", message: "unhandled error" } }));
        }
      });
      return;
    }

    res.writeHead(404, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: { code: "not_found", message: "not found" } }));
  });
}

const isMain =
  process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;

if (isMain) {
  const port = Number(process.env.PORT) || DEFAULT_PORT;
  const server = createSidecarServer();
  server.listen(port, () => {
    console.log(`ai-sidecar listening on port ${port}`);
  });
}
