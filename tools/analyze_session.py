"""Print record counts and Wi-Fi bands from exported RF Lab JSONL."""
import collections
import json
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: python tools/analyze_session.py session.jsonl")
counts = collections.Counter()
bands = collections.Counter()
rtts = []
with open(sys.argv[1], encoding="utf-8") as handle:
    for line in handle:
        if not line.strip():
            continue
        row = json.loads(line)
        counts[row["type"]] += 1
        p = row.get("payload", {})
        if row["type"] == "wifi_scan":
            for ap in p.get("aps", []):
                f = ap.get("frequency_mhz", 0)
                bands["2.4GHz" if f < 3000 else "5GHz" if f < 5925 else "6GHz"] += 1
        if row["type"] == "rtt_results":
            for r in p.get("results", []):
                if r.get("status") == 0 and "distance_mm" in r:
                    rtts.append(r["distance_mm"] / 1000)
print("Record types:", dict(counts))
print("WiFi readings per band:", dict(bands))
print("Successful RTT range estimates (m):", rtts)
