#!/usr/bin/env python3
"""语料校验：政策 frontmatter 字段完整性 + 工单 JSONL 全量解析。

单独成脚本而不是写在文档里，是为了让阶段 0 的验收可以随时重跑。
用法：python scripts/check-corpus.py
"""

from __future__ import annotations

import json
import re
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
POLICY_DIR = ROOT / "corpus" / "policies"
TICKET_DIR = ROOT / "corpus" / "tickets"

REQUIRED_FIELDS = ("doc_id", "title", "category", "version", "effective_at", "status")
EXPECTED_CATEGORIES = {
    "退换货", "运费", "退款", "质量", "物流", "大件", "生鲜", "发票", "价保", "会员", "兜底",
}
TICKET_FIELDS = (
    "ticket_id", "created_at", "channel", "category", "question", "answer",
    "related_doc", "resolution",
)

errors: list[str] = []
warnings: list[str] = []


def parse_frontmatter(path: Path) -> tuple[dict, str]:
    """解析 YAML frontmatter。只支持本语料用到的标量、空值与字符串列表，不引第三方 YAML 库。"""
    text = path.read_text(encoding="utf-8")
    m = re.match(r"^---\r?\n(.*?)\r?\n---\r?\n(.*)$", text, re.S)
    if not m:
        raise ValueError("缺少 YAML frontmatter")
    raw, body = m.group(1), m.group(2)

    data: dict[str, object] = {}
    current_list: str | None = None
    for line in raw.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if line.startswith("  - "):
            if current_list is None:
                raise ValueError(f"列表项没有归属键：{line!r}")
            # 空值键先被解析成 ""，遇到列表项时再转成列表
            if not isinstance(data.get(current_list), list):
                data[current_list] = []
            data[current_list].append(line.strip()[2:].strip())
            continue
        if ":" not in line:
            raise ValueError(f"无法解析的行：{line!r}")
        key, _, value = line.partition(":")
        key, value = key.strip(), value.strip()
        current_list = None
        if value == "":
            data[key] = ""
            current_list = key
        else:
            data[key] = value
    return data, body


def check_policies() -> tuple[int, int]:
    files = sorted(POLICY_DIR.rglob("*.md"))
    if not files:
        errors.append("corpus/policies/ 下没有政策文档")
        return 0, 0

    seen_ids: dict[str, Path] = {}
    active = retired = 0
    all_ids = {p.stem for p in files}

    for path in files:
        rel = path.relative_to(ROOT)
        try:
            meta, body = parse_frontmatter(path)
        except ValueError as exc:
            errors.append(f"{rel}: {exc}")
            continue

        for field in REQUIRED_FIELDS:
            if not meta.get(field):
                errors.append(f"{rel}: frontmatter 缺少 {field}")

        doc_id = str(meta.get("doc_id", ""))
        if doc_id and doc_id != path.stem:
            errors.append(f"{rel}: doc_id={doc_id} 与文件名不一致")
        if doc_id in seen_ids:
            errors.append(f"{rel}: doc_id 与 {seen_ids[doc_id].relative_to(ROOT)} 重复")
        seen_ids[doc_id] = path

        status = meta.get("status")
        if status == "active":
            active += 1
        elif status == "retired":
            retired += 1
            for field in ("retired_at", "superseded_by"):
                if not meta.get(field):
                    errors.append(f"{rel}: retired 文档缺少 {field}")
        else:
            errors.append(f"{rel}: status 只能是 active 或 retired，实际 {status!r}")

        category = meta.get("category")
        if category not in EXPECTED_CATEGORIES:
            errors.append(f"{rel}: category {category!r} 不在预期类目内")

        for field in ("effective_at", "retired_at"):
            value = meta.get(field)
            if value:
                try:
                    datetime.strptime(str(value), "%Y-%m-%d")
                except ValueError:
                    errors.append(f"{rel}: {field}={value} 不是 YYYY-MM-DD")

        for key in ("supersedes", "related"):
            targets = meta.get(key) or []
            if isinstance(targets, str):
                targets = [targets] if targets else []
            for target in targets:
                if target not in all_ids:
                    errors.append(f"{rel}: {key} 指向不存在的 doc_id {target}")

        if not body.strip():
            errors.append(f"{rel}: 正文为空")

    return active, retired


def check_tickets() -> Counter:
    files = sorted(TICKET_DIR.glob("*.jsonl"))
    if not files:
        errors.append("corpus/tickets/ 下没有工单文件")
        return Counter()

    counts: Counter = Counter()
    seen_ids: set[str] = set()
    total = 0

    for path in files:
        rel = path.relative_to(ROOT)
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                warnings.append(f"{rel}:{lineno}: 空行")
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                errors.append(f"{rel}:{lineno}: JSON 解析失败 {exc}")
                continue

            total += 1
            for field in TICKET_FIELDS:
                if not row.get(field):
                    errors.append(f"{rel}:{lineno}: 缺少字段 {field}")

            ticket_id = row.get("ticket_id", "")
            if ticket_id in seen_ids:
                errors.append(f"{rel}:{lineno}: ticket_id {ticket_id} 重复")
            seen_ids.add(ticket_id)

            try:
                datetime.fromisoformat(str(row.get("created_at")))
            except ValueError:
                errors.append(f"{rel}:{lineno}: created_at {row.get('created_at')!r} 不是 ISO 时间")

            category = row.get("category", "")
            counts[category] += 1
            if category not in EXPECTED_CATEGORIES:
                errors.append(f"{rel}:{lineno}: category {category!r} 不在预期类目内")

            if row.get("channel") not in {"app", "web", "phone"}:
                errors.append(f"{rel}:{lineno}: channel {row.get('channel')!r} 不在预期内")

    counts["__total__"] = total
    return counts


def main() -> int:
    # Windows 控制台默认按 GBK 编码输出，中文会乱码，这里强制 UTF-8
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")

    active, retired = check_policies()
    ticket_counts = check_tickets()
    total_tickets = ticket_counts.pop("__total__", 0)

    print(f"政策文档：active {active} 篇，retired {retired} 篇")
    print(f"历史工单：{total_tickets} 条")
    print("类目分布：" + "，".join(f"{k} {v}" for k, v in sorted(ticket_counts.items())))

    if active < 10:
        errors.append(f"active 政策文档 {active} 篇，少于要求的 10 篇")
    if total_tickets < 50:
        errors.append(f"历史工单 {total_tickets} 条，少于要求的 50 条")

    for warning in warnings:
        print(f"[warn] {warning}")
    for error in errors:
        print(f"[FAIL] {error}")

    print("校验" + ("通过" if not errors else f"失败，共 {len(errors)} 项"))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
