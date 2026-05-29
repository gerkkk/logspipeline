from __future__ import annotations

import json
import os
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

TIMESTAMP_RE = re.compile(r"^\d{2}\.\d{2}\.\d{4}_\d{2}:\d{2}:\d{2}$")
TIMESTAMP_HTTP_RE = re.compile(
    r"^(Mon|Tue|Wed|Thu|Fri|Sat|Sun),_\d{1,2}_[A-Za-z]{3}_\d{4}_\d{2}:\d{2}:\d{2}_\+\d{4}$"
)


def is_timestamp(token: str) -> bool:
    return bool(TIMESTAMP_RE.match(token) or TIMESTAMP_HTTP_RE.match(token))
DOC_ID_RE = re.compile(r"[A-Z]+_\d+")
SEARCH_ID_RE = re.compile(r"^-?\d+$")
CARD_QUERY_RE = re.compile(r"^\$\d+")

KNOWN_COMMANDS = frozenset({
    "SESSION_START",
    "SESSION_END",
    "QS",
    "DOC_OPEN",
    "CARD_SEARCH_START",
    "CARD_SEARCH_END",
})

RECORD_FORMAT_SPECS: Dict[str, Dict[str, Any]] = {
    "SESSION_START": {
        "pattern": "SESSION_START <timestamp>",
        "fields": {
            "timestamp": "dd.MM.yyyy_HH:mm:ss | Mon,_d_Mon_yyyy_HH:mm:ss_+ZZZZ",
        },
        "notes": "Начало сессии; один файл = одна сессия.",
    },
    "SESSION_END": {
        "pattern": "SESSION_END <timestamp>",
        "fields": {
            "timestamp": "dd.MM.yyyy_HH:mm:ss | Mon,_d_Mon_yyyy_HH:mm:ss_+ZZZZ",
        },
    },
    "QUICK_SEARCH": {
        "pattern": "QS <timestamp> {<query_text>}",
        "fields": {
            "timestamp": "dd.MM.yyyy_HH:mm:ss | Mon,_d_Mon_yyyy_HH:mm:ss_+ZZZZ",
            "query_text": "текст в фигурных скобках (может быть пустым)",
        },
        "notes": "Следующая строка — SEARCH_RESULTS с search_id и doc_id.",
    },
    "SEARCH_RESULTS": {
        "pattern": "<search_id> <doc_id> [<doc_id> ...]",
        "fields": {
            "search_id": "целое, может быть отрицательным",
            "document_ids": "список PREFIX_number через пробел",
        },
        "notes": "Строка без явной команды; привязана к предыдущему QS или CARD_SEARCH.",
    },
    "DOCUMENT_OPEN": {
        "pattern": "DOC_OPEN <timestamp> <search_id> <document_id>",
        "fields": {
            "timestamp": "dd.MM.yyyy_HH:mm:ss | HTTP-вариант",
            "search_id": "целое",
            "document_id": "PREFIX_number",
        },
    },
    "DOCUMENT_OPEN_NO_TS": {
        "pattern": "DOC_OPEN <search_id> <document_id>",
        "fields": {
            "search_id": "целое",
            "document_id": "PREFIX_number",
        },
        "notes": "~6% DOC_OPEN: timestamp отсутствует (двойной пробел после DOC_OPEN в сыром логе).",
    },
    "CARD_SEARCH_START": {
        "pattern": "CARD_SEARCH_START <timestamp>",
        "fields": {"timestamp": "dd.MM.yyyy_HH:mm:ss"},
        "notes": "Далее CARD_SEARCH_QUERY, затем CARD_SEARCH_END, затем SEARCH_RESULTS.",
    },
    "CARD_SEARCH_QUERY": {
        "pattern": "$<n> [<free text>] | $<n> <document_id>",
        "fields": {
            "card_param": "например $134 или $0",
            "query_text": "остаток строки (опционально)",
            "document_id": "редко: doc_id сразу после $0",
        },
    },
    "CARD_SEARCH_END": {
        "pattern": "CARD_SEARCH_END",
        "fields": {},
        "notes": "Без timestamp; часто строка только из команды.",
    },
    "EMPTY": {
        "pattern": "(пустая строка)",
        "fields": {},
    },
    "UNPARSEABLE": {
        "pattern": "?",
        "fields": {},
    },
}


@dataclass
class ParsedLine:
    record_type: str
    raw: str
    fields: Dict[str, Any] = field(default_factory=dict)
    parse_error: Optional[str] = None


def classify_line(line: str) -> ParsedLine:
    raw = line.rstrip("\n\r")
    stripped = raw.strip()
    if not stripped:
        return ParsedLine("EMPTY", raw)

    parts = stripped.split()
    first = parts[0]

    if SEARCH_ID_RE.match(first):
        second = parts[1] if len(parts) > 1 else ""
        if len(parts) == 1 or not is_timestamp(second):
            doc_ids = DOC_ID_RE.findall(stripped)
            return ParsedLine(
                "SEARCH_RESULTS",
                raw,
                {
                    "search_id": first,
                    "document_ids": doc_ids,
                    "document_count": len(doc_ids),
                },
            )

    if CARD_QUERY_RE.match(first):
        rest = stripped[len(first) :].strip()
        doc_match = DOC_ID_RE.search(rest) if rest else None
        fields: Dict[str, Any] = {"card_param": first}
        if doc_match and rest == doc_match.group(0):
            fields["document_id"] = doc_match.group(0)
        elif rest:
            fields["query_text"] = rest
        return ParsedLine("CARD_SEARCH_QUERY", raw, fields)

    if first not in KNOWN_COMMANDS:
        return ParsedLine("UNPARSEABLE", raw, parse_error=f"unknown command: {first!r}")

    if first == "CARD_SEARCH_END":
        if len(parts) == 1:
            return ParsedLine("CARD_SEARCH_END", raw)
        if len(parts) == 2 and is_timestamp(parts[1]):
            return ParsedLine("CARD_SEARCH_END", raw, {"timestamp": parts[1]})
        return ParsedLine(
            "UNPARSEABLE",
            raw,
            parse_error="CARD_SEARCH_END with unexpected tokens",
        )

    if len(parts) < 2:
        return ParsedLine("UNPARSEABLE", raw, parse_error="missing timestamp")

    timestamp = parts[1]
    if not is_timestamp(timestamp):
        if first == "DOC_OPEN" and len(parts) >= 3:
            return ParsedLine(
                "DOCUMENT_OPEN_NO_TS",
                raw,
                {
                    "search_id": parts[1],
                    "document_id": parts[2],
                },
            )
        return ParsedLine(
            "UNPARSEABLE",
            raw,
            parse_error=f"invalid timestamp: {timestamp!r}",
        )

    if first in ("SESSION_START", "SESSION_END"):
        return ParsedLine(first, raw, {"timestamp": timestamp})

    if first == "CARD_SEARCH_START":
        rest = stripped.split(None, 2)
        extra = rest[2].strip() if len(rest) > 2 else ""
        fields = {"timestamp": timestamp}
        if extra:
            fields["params"] = extra
        return ParsedLine("CARD_SEARCH_START", raw, fields)

    if first == "QS":
        rest = stripped.split(None, 2)
        body = rest[2] if len(rest) > 2 else ""
        m = re.match(r"\{([^}]*)\}\s*(.*)", body, re.DOTALL)
        fields = {"timestamp": timestamp}
        if m:
            fields["query_text"] = m.group(1)
            tail = m.group(2).strip()
            if tail:
                fields["document_ids_inline"] = DOC_ID_RE.findall(tail)
                fields["tail_raw"] = tail
        else:
            fields["query_raw"] = body
        return ParsedLine("QUICK_SEARCH", raw, fields)

    if first == "DOC_OPEN":
        if len(parts) == 4:
            return ParsedLine(
                "DOCUMENT_OPEN",
                raw,
                {
                    "timestamp": timestamp,
                    "search_id": parts[2],
                    "document_id": parts[3],
                },
            )
        if len(parts) == 3:
            return ParsedLine(
                "DOCUMENT_OPEN_NO_TS",
                raw,
                {
                    "search_id": parts[1],
                    "document_id": parts[2],
                },
            )
        return ParsedLine(
            "UNPARSEABLE",
            raw,
            parse_error=f"DOC_OPEN token count={len(parts)}",
        )

    return ParsedLine("UNPARSEABLE", raw, parse_error="unhandled command")


def analyze_file(filepath: str, agg: Dict[str, Any]) -> int:
    events = 0
    with open(filepath, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            parsed = classify_line(line)
            if parsed.record_type == "EMPTY":
                agg["empty_lines"] += 1
                continue

            events += 1
            rt = parsed.record_type
            agg["record_counts"][rt] += 1

            if parsed.parse_error:
                agg["parse_errors"][parsed.parse_error] += 1
                if len(agg["unparseable_samples"]) < 20:
                    agg["unparseable_samples"].append(
                        {"line": parsed.raw[:200], "error": parsed.parse_error}
                    )

            samples: List[Dict[str, Any]] = agg["samples"].setdefault(rt, [])
            if len(samples) < 2:
                samples.append({"raw": parsed.raw[:160], "fields": parsed.fields})

            if rt == "QUICK_SEARCH":
                q = parsed.fields.get("query_text")
                if q is not None and not str(q).strip():
                    agg["qs_empty_query"] += 1
                if is_timestamp(parsed.fields.get("timestamp", "")) and TIMESTAMP_HTTP_RE.match(
                    parsed.fields["timestamp"]
                ):
                    agg["timestamp_http_format"] += 1
                if parsed.fields.get("document_ids_inline"):
                    agg["qs_inline_doc_ids"] += 1
            elif rt == "SEARCH_RESULTS":
                agg["search_results_doc_counts"][parsed.fields.get("document_count", 0)] += 1
                sid = parsed.fields.get("search_id", "")
                if str(sid).startswith("-"):
                    agg["negative_search_id"] += 1
            elif rt == "DOCUMENT_OPEN":
                agg["doc_open_with_ts"] += 1
            elif rt == "DOCUMENT_OPEN_NO_TS":
                agg["doc_open_no_ts"] += 1
            elif rt == "CARD_SEARCH_QUERY":
                agg["card_param_counts"][parsed.fields.get("card_param", "?")] += 1
                if parsed.fields.get("document_id"):
                    agg["card_query_with_doc_id"] += 1

            if "timestamp" in parsed.fields:
                date = parsed.fields["timestamp"].split("_", 1)[0]
                agg["date_formats"][date] += 1

            for doc_id in parsed.fields.get("document_ids", []):
                prefix = doc_id.split("_", 1)[0]
                agg["doc_prefixes"][prefix] += 1
            if "document_id" in parsed.fields:
                agg["doc_prefixes"][parsed.fields["document_id"].split("_", 1)[0]] += 1

    return events


def resolve_data_dir() -> str:
    script_dir = os.path.dirname(os.path.abspath(__file__))
    candidate = os.path.join(script_dir, "data")
    if os.path.isdir(candidate):
        return candidate
    return os.path.join(os.getcwd(), "data")


def main() -> None:
    data_dir = resolve_data_dir()
    if not os.path.isdir(data_dir):
        print(f"Директория не найдена: {data_dir}", file=sys.stderr)
        sys.exit(1)

    agg: Dict[str, Any] = {
        "record_counts": Counter(),
        "parse_errors": Counter(),
        "unparseable_samples": [],
        "samples": {},
        "empty_lines": 0,
        "qs_empty_query": 0,
        "timestamp_http_format": 0,
        "qs_inline_doc_ids": 0,
        "negative_search_id": 0,
        "doc_open_with_ts": 0,
        "doc_open_no_ts": 0,
        "card_param_counts": Counter(),
        "card_query_with_doc_id": 0,
        "search_results_doc_counts": Counter(),
        "date_formats": Counter(),
        "doc_prefixes": Counter(),
    }

    files_processed = 0
    total_events = 0

    print(f"Каталог данных: {data_dir}\n")
    for i in range(10000):
        filepath = os.path.join(data_dir, str(i))
        if not os.path.isfile(filepath):
            continue
        n = analyze_file(filepath, agg)
        if n > 0:
            files_processed += 1
            total_events += n
            if files_processed % 1000 == 0:
                print(f"  … {files_processed} файлов, {total_events} строк-событий")

    print(f"\n{'=' * 80}")
    print("СВОДКА")
    print(f"{'=' * 80}")
    print(f"Файлов (сессий):     {files_processed}")
    print(f"Строк-событий:       {total_events}")
    print(f"Пустых строк:        {agg['empty_lines']}")

    unparseable = agg["record_counts"].get("UNPARSEABLE", 0)
    print(f"Неразобранных строк: {unparseable}")

    print(f"\n{'=' * 80}")
    print("ТИПЫ ЗАПИСЕЙ (record_type)")
    print(f"{'=' * 80}")
    for rt, count in agg["record_counts"].most_common():
        pct = 100.0 * count / total_events if total_events else 0
        spec = RECORD_FORMAT_SPECS.get(rt, {})
        pattern = spec.get("pattern", "—")
        print(f"\n  {rt}")
        print(f"    count:   {count:>8}  ({pct:5.2f}%)")
        print(f"    format:  {pattern}")
        if spec.get("notes"):
            print(f"    note:    {spec['notes']}")

    print(f"\n{'=' * 80}")
    print("ДОПОЛНИТЕЛЬНО")
    print(f"{'=' * 80}")
    print(f"  QS с пустым {{}}:              {agg['qs_empty_query']}")
    print(f"  Строк с HTTP-timestamp:        {agg['timestamp_http_format']}")
    print(f"  QS с doc_id в той же строке:   {agg['qs_inline_doc_ids']}")
    print(f"  SEARCH_RESULTS (search_id<0):  {agg['negative_search_id']}")
    print(f"  DOC_OPEN с timestamp:          {agg['doc_open_with_ts']}")
    print(f"  DOC_OPEN без timestamp:        {agg['doc_open_no_ts']}")
    print(f"  CARD_QUERY с doc_id на строке: {agg['card_query_with_doc_id']}")
    print(f"  CARD param ($n):                {dict(agg['card_param_counts'])}")

    if agg["parse_errors"]:
        print("\n  Ошибки парсинга:")
        for err, c in agg["parse_errors"].most_common(10):
            print(f"    {err}: {c}")

    print(f"\n  Топ префиксов doc_id: {agg['doc_prefixes'].most_common(15)}")
    print(f"\n  Документов в выдаче (SEARCH_RESULTS), топ размеров:")
    for size, c in agg["search_results_doc_counts"].most_common(8):
        print(f"    {size} doc_id: {c} строк")

    out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "log_analysis_stats.json")
    report = {
        "files_processed": files_processed,
        "total_events": total_events,
        "empty_lines": agg["empty_lines"],
        "unparseable_lines": unparseable,
        "record_counts": dict(agg["record_counts"]),
        "record_formats": RECORD_FORMAT_SPECS,
        "samples_by_type": agg["samples"],
        "qs_empty_query": agg["qs_empty_query"],
        "timestamp_http_format_lines": agg["timestamp_http_format"],
        "qs_inline_doc_ids": agg["qs_inline_doc_ids"],
        "negative_search_id_lines": agg["negative_search_id"],
        "doc_open_with_ts": agg["doc_open_with_ts"],
        "doc_open_no_ts": agg["doc_open_no_ts"],
        "card_param_counts": dict(agg["card_param_counts"]),
        "card_query_with_doc_id": agg["card_query_with_doc_id"],
        "doc_prefixes_top30": agg["doc_prefixes"].most_common(30),
        "search_results_doc_count_distribution": dict(
            agg["search_results_doc_counts"].most_common(20)
        ),
        "date_formats_top20": agg["date_formats"].most_common(20),
        "parse_errors": dict(agg["parse_errors"]),
        "unparseable_samples": agg["unparseable_samples"],
        "commands": _legacy_commands_block(agg),
    }

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)

    print(f"\n Отчёт: {out_path}")


def _legacy_commands_block(agg: Dict[str, Any]) -> Dict[str, Any]:
    mapping = {
        "SESSION_START": "SESSION_START",
        "SESSION_END": "SESSION_END",
        "QUICK_SEARCH": "QS",
        "SEARCH_RESULTS": "NUMERIC_COMMAND",
        "DOCUMENT_OPEN": "DOC_OPEN",
        "DOCUMENT_OPEN_NO_TS": "DOC_OPEN",
        "CARD_SEARCH_START": "CARD_SEARCH_START",
        "CARD_SEARCH_END": "CARD_SEARCH_END",
        "CARD_SEARCH_QUERY": "CARD_SEARCH_QUERY",
    }
    legacy: Dict[str, int] = defaultdict(int)
    for rt, count in agg["record_counts"].items():
        name = mapping.get(rt, rt)
        legacy[name] += count
    return {k: {"total_occurrences": v} for k, v in sorted(legacy.items())}


if __name__ == "__main__":
    main()
