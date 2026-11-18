#!/usr/bin/env python3
"""把 M2 任务写入队列，并在首行记录证据 fact id（runner 会读取并传给制品）。"""
import argparse, pathlib, sys
p = argparse.ArgumentParser()
p.add_argument("--fact", type=int, required=True)
p.add_argument("--task", required=True)
p.add_argument("--queue", default="/home/openclaw/m2/queue/current.md")
args = p.parse_args()
task = args.task.strip()
if args.fact <= 0 or not task:
    print("fact 必须为正整数，task 不能为空", file=sys.stderr); sys.exit(2)
q = pathlib.Path(args.queue)
q.parent.mkdir(parents=True, exist_ok=True)
q.write_text(f"fact: {args.fact}\n\n{task}\n")
print(f"queued fact={args.fact} file={q}")
