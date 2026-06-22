#!/usr/bin/env python3
"""
aggregate_aol_log.py

Aggregates a raw AOL search query log (tab-separated:
AnonID, Query, QueryTime, ItemRank, ClickURL) into a (query, count) CSV
suitable for bulk-loading into the search_query table.

This matches assignment section 3: "If the chosen dataset does not
already include counts, students may derive counts by aggregation."

Usage:
    python3 aggregate_aol_log.py <input_log.txt> [more_logs.txt ...] -o queries.csv

Cleaning applied (documented for the viva):
  - Skips the header row if present.
  - Skips rows with a missing/blank query, or the literal "-" placeholder
    that appears in the AOL log for empty searches.
  - Strips leading/trailing whitespace.
  - Aggregates case-insensitively is NOT done here (we keep the original
    casing of the *first* occurrence as the canonical display form) -
    counting itself is done on the lower-cased query, since "iPhone" and
    "iphone" are the same search intent for a typeahead system.
  - Multiple input files can be passed in one run (the full AOL release
    ships as ~10 files); counts are merged across all of them.
"""
import argparse
import csv
import sys
from collections import defaultdict


def clean_query(raw: str) -> str | None:
    q = raw.strip()
    if not q or q == "-":
        return None
    return q


def aggregate(paths: list[str]) -> dict[str, tuple[str, int]]:
    """Returns {query_lower: (display_form, count)}"""
    counts: dict[str, int] = defaultdict(int)
    display_form: dict[str, str] = {}

    for path in paths:
        print(f"Reading {path} ...", file=sys.stderr)
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            reader = csv.reader(f, delimiter="\t")
            first_row = True
            for row in reader:
                if first_row:
                    first_row = False
                    # AOL files have a header row "AnonID Query QueryTime ItemRank ClickURL"
                    if len(row) > 1 and row[1].strip().lower() == "query":
                        continue
                if len(row) < 2:
                    continue
                query = clean_query(row[1])
                if query is None:
                    continue
                key = query.lower()
                counts[key] += 1
                if key not in display_form:
                    display_form[key] = query

    return {k: (display_form[k], v) for k, v in counts.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", help="One or more raw AOL log .txt files")
    parser.add_argument("-o", "--output", default="queries.csv", help="Output CSV path")
    parser.add_argument("--min-count", type=int, default=1,
                         help="Drop queries searched fewer than this many times (default: 1, keep all)")
    args = parser.parse_args()

    aggregated = aggregate(args.inputs)
    print(f"Aggregated {len(aggregated)} distinct queries from {len(args.inputs)} file(s)", file=sys.stderr)

    rows = [(disp, cnt) for disp, cnt in aggregated.values() if cnt >= args.min_count]
    rows.sort(key=lambda r: r[1], reverse=True)

    with open(args.output, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["query", "count"])
        writer.writerows(rows)

    print(f"Wrote {len(rows)} rows to {args.output}", file=sys.stderr)
    if rows:
        print(f"Top query: {rows[0][0]!r} with count {rows[0][1]}", file=sys.stderr)


if __name__ == "__main__":
    main()
