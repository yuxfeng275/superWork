#!/usr/bin/env python3
"""把 M2 patch 登记为 FIX_NOTE 并送审（调用现有 /api/ai-artifacts）。"""
import argparse, json, urllib.request, urllib.error, os, sys

def call(base, path, method="GET", body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base.rstrip("/") + path, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read() or b"{}")

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", required=True)
    p.add_argument("--user", default="admin")
    p.add_argument("--password", required=True)
    p.add_argument("--project", type=int, required=True)
    p.add_argument("--fact", type=int, required=True)
    p.add_argument("--branch", required=True)
    p.add_argument("--patch", required=True)
    p.add_argument("--task", default="")
    args = p.parse_args()
    task = args.task.strip()
    if not os.path.isfile(args.patch) or os.path.getsize(args.patch) == 0:
        print("patch 为空，拒绝建制品", file=sys.stderr)
        return 2
    status, login = call(args.base, "/api/auth/login", "POST",
                         {"username": args.user, "password": args.password})
    token = (login.get("data") or {}).get("accessToken")
    if status != 200 or not token:
        print(f"登录失败 HTTP {status}", file=sys.stderr)
        return 3
    content = (f"M2 夜间队列自动产出。\n分支：{args.branch}\n补丁：{args.patch}\n"
               f"任务：{task[:500]}\n主分支未改动；请评审后合并。")
    status, art = call(args.base, "/api/ai-artifacts", "POST", {
        "title": f"M2 夜间补丁：{task[:80] or args.branch}",
        "kind": "FIX_NOTE", "content": content,
        "evidenceJson": json.dumps([args.fact]), "branch": args.branch,
        "projectId": args.project,
    }, token)
    aid = (art.get("data") or {}).get("id")
    if status != 200 or not aid:
        print(f"建制品失败 HTTP {status} {art.get('message')}", file=sys.stderr)
        return 4
    status, submitted = call(args.base, f"/api/ai-artifacts/{aid}/submit", "POST", None, token)
    print(f"artifact={aid} submit_http={status} status={(submitted.get('data') or {}).get('status')}")
    return 0 if status == 200 else 5

if __name__ == "__main__":
    sys.exit(main())
