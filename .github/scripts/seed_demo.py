#!/usr/bin/env python3
"""Generate demo data (entries.txt + draft.xml) in the app's on-disk formats.

usage: seed_demo.py OUTDIR TZ_OFFSET_MINUTES
Formats mirror Store.kt: entries are '|' joined lines
ts|anxiety|caffeine|food|exercise|partner|wakeMin|sleep|causes(;)|medDose|medTime|medUnit|medName
and prefs live in shared_prefs/draft.xml.
"""
import os
import random
import sys
import time

out = sys.argv[1]
tz = int(sys.argv[2]) if len(sys.argv) > 2 else 0
random.seed(7)

now_ms = int(time.time() * 1000)
local_now = now_ms + tz * 60000
today = local_now // 86400000          # epoch day on the device
causes_pool = ["Work", "Deadline", "News/phone", "Money", "Social", "Conflict", "Health worry"]


def anxiety_at(h):
    base = 2.5 + 2.5 * (1 if 9 <= h <= 11 else 0) + 3.0 * (1 if 14 <= h <= 17 else 0) + 1.0 * (1 if h >= 20 else 0)
    return max(1, min(10, round(base + random.gauss(0, 1.2))))


lines = []
for back in range(14, -1, -1):
    day = today - back
    wake = random.choice([405, 420, 435, 450])
    sleep = random.choice([2, 3, 3, 4])
    exercise = random.random() < 0.5
    took_med = random.random() < 0.85
    day_dose = "10" if back > 5 else "15"
    med_time = wake + 45
    n = random.randint(3, 5)
    hours = sorted(random.sample(range(8, 22), n))
    for h in hours:
        minute = random.randint(0, 59)
        local_ms = day * 86400000 + (h * 60 + minute) * 60000
        ts = local_ms - tz * 60000
        if ts > now_ms - 60000:
            continue
        a = anxiety_at(h)
        if a >= 6:
            cs = random.sample(causes_pool[:3], random.randint(1, 2))
        elif a >= 4:
            cs = random.sample(causes_pool, 1)
        else:
            cs = ["No clear reason"] if random.random() < 0.5 else []
        f = lambda v: "" if v is None else str(v)
        b = lambda v: "1" if v else "0"
        dose = day_dose if (took_med and h * 60 + minute >= med_time) else ""
        lines.append("|".join([
            str(ts), str(a), b(random.random() < 0.6), b(random.random() < 0.7),
            b(exercise and h >= 12), str(random.choice([0, 0, 1, 2])), str(wake), str(sleep),
            ";".join(cs), dose, str(med_time) if dose else "", "mg" if dose else "", "",
        ]))

os.makedirs(out, exist_ok=True)
with open(os.path.join(out, "entries.txt"), "w") as fh:
    fh.write("\n".join(lines))

prefs = [
    ("int", "anxiety", 6), ("int", "caffeine", 1), ("int", "food", 1), ("int", "exercise", 1),
    ("long", "exerciseDay", today), ("int", "partner", 1),
    ("int", "wakeMin", 420), ("long", "wakeDay", today),
    ("int", "sleep", 3), ("long", "sleepDay", today),
    ("string", "medDoseNum", "15.0"), ("string", "medUnit", "mg"), ("int", "medUsualTime", 480),
    ("int", "intervalHours", 3),
    ("string", "medName", ""),
]
with open(os.path.join(out, "draft.xml"), "w") as fh:
    fh.write("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n")
    for t, k, v in prefs:
        if t == "string":
            fh.write('    <string name="%s">%s</string>\n' % (k, str(v).replace("\n", "&#10;")))
        else:
            fh.write('    <%s name="%s" value="%s" />\n' % (t, k, v))
    fh.write("</map>\n")
print("entries:", len(lines))
