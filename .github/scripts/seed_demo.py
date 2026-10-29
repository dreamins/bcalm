#!/usr/bin/env python3
"""Generate anonymous demo data in the app's on-disk formats (mirrors Store.kt).

usage: seed_demo.py OUTDIR TZ_OFFSET_MINUTES
Writes OUTDIR/entries.txt, OUTDIR/prompts.txt and OUTDIR/draft.xml (SharedPreferences "draft").
entries.txt lines ('|' joined):
  ts|anxiety|caffeine|food|exercise|partner|wakeMin|sleep|causes(;)|medDose|medTime|medUnit|medName|nightSweats|
  alcohol|cannabis|offsetMin|joy|after|tools(;)|source|promptId
prompts.txt lines: id|scheduledTs|window|status|answeredTs
Three weeks: manual entries skewed to upset moments (5 bad days, 8 peaks; ordinary days 0-2 small logs), random check-ins (~80% answered, mostly 1-3),
follow-ups that show spikes fading, a couple of recalled entries, wake times, some joy values, tools on some spikes.
"""
import datetime
import os
import random
import sys
import time

out = sys.argv[1]
tz = int(sys.argv[2]) if len(sys.argv) > 2 else 0
random.seed(11)

DAYS = 21
BAD_BACK = {18: 2, 13: 2, 9: 1, 5: 2, 1: 1}   # bad days -> number of 7-8 peaks (8 in all)
now_ms = int(time.time() * 1000)
today = (now_ms + tz * 60000) // 86400000    # epoch day on the device
CUTOFF = now_ms - 90 * 60000                 # nothing in the last 90 min (no open calm card / pending noise)
WINDOWS = [(9 * 60, 11 * 60), (14 * 60, 16 * 60), (19 * 60, 21 * 60)]   # Prompts.DEFAULTS
FOLLOW_MIN = [30, 90, 240]
FOLLOWUP_WINDOW = 10

UPSET = ["Work deadline", "Conflict at home", "Rumination", "Money worry", "News/phone", "No clear reason"]
GOOD = ["Walk", "Friend call", "Good sleep"]
CAUSE_LIST = UPSET + GOOD


def ts_of(day, minute):
    return day * 86400000 + minute * 60000 - tz * 60000


def clamp(v, lo=1, hi=10):
    return max(lo, min(hi, int(round(v))))


def hour_weighted():
    hs = list(range(8, 22))
    w = [2 if 9 <= h <= 11 else 3 if 14 <= h <= 17 else 2 if h >= 20 else 1 for h in hs]
    return random.choices(hs, w)[0]


entries = []   # dicts
prompts = []   # (id, ts, window, status, answeredTs)


def entry(ts, anx, wake, sleep, causes, day_flags, exercise, partner, source="manual", pid="", joy="", after="", tools=""):
    local_min = ((ts + tz * 60000) // 60000) % 1440
    dose = day_flags["dose"] if (day_flags["took_med"] and local_min >= day_flags["med_time"]) else ""
    b = lambda v: "1" if v else "0"
    entries.append((ts, "|".join([
        str(ts), "" if anx is None else str(anx), b(random.random() < 0.6), b(random.random() < 0.7),
        b(exercise and local_min >= 12 * 60), str(partner), str(wake), str(sleep), ";".join(causes),
        dose, str(day_flags["med_time"]) if dose else "", "mg" if dose else "", "",
        b(day_flags["sweats"]), b(day_flags["alcohol"]), b(day_flags["cannabis"]), str(tz),
        str(joy), str(after), tools, source, pid,
    ])))


prev_alcohol = False
prev_mean = 3.0
recalled_days = {8, 4}
for back in range(DAYS - 1, -1, -1):
    day = today - back
    d = datetime.date.fromordinal(day + 719163)      # epoch day -> date
    bad = back in BAD_BACK
    wake = random.choice([405, 420, 435, 450])
    sleep = random.choice([1, 2, 2]) if bad else random.choice([3, 3, 4, 4, 2])
    exercise = (not bad) and random.random() < 0.5
    flags = {
        "took_med": random.random() < 0.85, "dose": "5" if back > 10 else "10", "med_time": wake + 45,
        "alcohol": random.random() < 0.25, "cannabis": random.random() < 0.12,
    }
    flags["sweats"] = random.random() < (0.55 if prev_alcohol else 0.15) + (0.2 if prev_mean >= 4 else 0)
    day_vals = []

    # --- manual entries, skewed to upset moments ---
    if bad:
        levels = [random.choice([7, 8]) for _ in range(BAD_BACK[back])] + [random.choice([5, 6]) for _ in range(random.choice([1, 2]))]
        random.shuffle(levels)
    else:
        levels = [random.choice([3, 3, 4, 4, 5]) for _ in range(random.choice([0, 1, 1, 2]))]
    n = len(levels)
    mins = sorted(random.sample(range(8, 22), n))
    spikes = []
    for i, (h, lv) in enumerate(zip(mins, levels)):
        m = h * 60 + random.randint(0, 50)
        ts = ts_of(day, m)
        if ts > CUTOFF:
            continue
        partner = 2 if random.random() < (0.5 if bad else 0.15) else random.choice([0, 0, 1])
        causes = random.sample(UPSET[:5], random.choice([1, 1, 2]))
        if partner == 2 and "Conflict at home" not in causes:
            causes[0] = "Conflict at home"
        after, tools = "", ""
        if lv >= 6 and random.random() < 0.45:
            tl = random.choice([["breathe"], ["ground"], ["card", "breathe"]])
            tools = ";".join(tl)
            after = clamp(lv - random.choice([2, 2, 3]), 1)
        entry(ts, lv, wake, sleep, causes, flags, exercise, partner, "manual", "", "", after, tools)
        day_vals.append(lv)
        spikes.append((m, lv))
    # --- follow-ups after spikes: fades toward baseline ~2 ---
    for si, (m, lv) in enumerate(spikes):
        if lv < 5:
            continue
        nxt = spikes[si + 1][0] if si + 1 < len(spikes) else 24 * 60
        sdt = datetime.datetime(d.year, d.month, d.day, m // 60, m % 60)
        prev = lv
        for k, fm in enumerate(FOLLOW_MIN):
            t = m + fm
            if t >= nxt or t < 7 * 60 or t > 22 * 60 + 30:
                break
            pts = ts_of(day, t)
            if pts > CUTOFF:
                break
            pid = "f" + sdt.strftime("%Y%m%d%H%M") + "-" + str(k)
            if random.random() < 0.8:
                dec = {0: 0.8, 1: 0.55, 2: 0.25}[k]
                v = clamp(min(prev, 2 + (lv - 2) * dec + random.uniform(-0.6, 0.6)), 1, prev)
                ats = pts + random.randint(1, 12) * 60000
                prompts.append((pid, pts, FOLLOWUP_WINDOW + k, "answered", ats))
                entry(ats, v, wake, sleep, [], flags, exercise, 1, "followup", pid)
                prev = v
                day_vals.append(v)
                if v <= 3:
                    break
            else:
                prompts.append((pid, pts, FOLLOWUP_WINDOW + k, random.choice(["expired", "skipped"]), ""))
    # --- random check-ins ---
    for w, (a, b_) in enumerate(WINDOWS):
        m = random.randint(a, b_ - 10)
        sts = ts_of(day, m)
        if sts > CUTOFF:
            continue
        pid = "p" + d.strftime("%Y%m%d") + "-" + str(w)
        r = random.random()
        if r < 0.8:
            base = [2, 2, 2, 3] if w == 0 else [2, 2, 3, 3] if w == 1 else [1, 2, 2, 3]
            v = random.choice(base) + (2 if bad else 0) + (1 if prev_mean >= 4.5 and random.random() < 0.2 else 0)
            v = clamp(v, 1, 6)
            ats = sts + random.randint(2, 25) * 60000
            prompts.append((pid, sts, w, "answered", ats))
            entry(ats, v, wake, sleep, [], flags, exercise, 1, "prompted", pid)
            day_vals.append(v)
        else:
            prompts.append((pid, sts, w, "skipped" if r < 0.92 else "expired", ""))
    # --- recalled entries ("Earlier today...") ---
    if back in recalled_days:
        m = random.randint(9 * 60, 13 * 60)
        ts = ts_of(day, m)
        if ts <= CUTOFF:
            entry(ts, 6, wake, sleep, ["Rumination"], flags, exercise, 1, "recalled")
    # --- joy-only entries after something good ---
    for _ in range(random.choice([0, 1, 1, 2])):
        m = random.randint(10 * 60, 21 * 60)
        ts = ts_of(day, m)
        if ts > CUTOFF:
            continue
        joy = random.choice([3, 4, 4, 5] if flags["dose"] == "5" else [2, 3, 3, 4])
        entry(ts, None, wake, sleep, [random.choice(GOOD)], flags, exercise, random.choice([0, 1]), "manual", "", joy)
    prev_alcohol = flags["alcohol"]
    if day_vals:
        prev_mean = sum(day_vals) / len(day_vals)

os.makedirs(out, exist_ok=True)
entries.sort()
with open(os.path.join(out, "entries.txt"), "w") as fh:
    fh.write("\n".join(l for _, l in entries))
prompts.sort(key=lambda p: p[1])
with open(os.path.join(out, "prompts.txt"), "w") as fh:
    fh.write("\n".join("|".join(str(x) for x in p) for p in prompts))

prefs = [
    ("int", "anxiety", 3), ("int", "caffeine", 1), ("int", "food", 1), ("int", "exercise", 1),
    ("long", "exerciseDay", today), ("int", "partner", 1),
    ("int", "wakeMin", 420), ("long", "wakeDay", today),
    ("int", "sleep", 3), ("long", "sleepDay", today),
    ("int", "nightSweats", 0), ("long", "nightSweatsDay", today),
    ("int", "alcohol", 0), ("long", "alcoholDay", today),
    ("int", "cannabis", 0), ("long", "cannabisDay", today),
    ("string", "medDoseNum", "10.0"), ("string", "medUnit", "mg"), ("int", "medUsualTime", 480),
    ("string", "medName", ""),
    ("string", "causeList", "\n".join(CAUSE_LIST)),
    # the calm intro must never show in screenshots; old reminder is already "migrated"
    ("long", "introSeenVersion", 999999999), ("boolean", "promptsMigrated", "true"),
    ("boolean", "promptsOn", "true"), ("boolean", "followupsOn", "true"),
    # chart view/mode are rewritten by screenshots.sh before each launch
    ("int", "chartView3", 0), ("boolean", "chartModeled", "true"),
]
with open(os.path.join(out, "draft.xml"), "w") as fh:
    fh.write("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n")
    for t, k, v in prefs:
        if t == "string":
            fh.write('    <string name="%s">%s</string>\n' % (k, str(v).replace("\n", "&#10;")))
        else:
            fh.write('    <%s name="%s" value="%s" />\n' % (t, k, v))
    fh.write("</map>\n")
print("entries:", len(entries), "prompts:", len(prompts))
