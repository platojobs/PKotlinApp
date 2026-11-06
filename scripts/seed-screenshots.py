#!/usr/bin/env python3
"""
为 docs/screenshots 造一份可复现的演示数据（不是生产数据）。

用法：
  python3 scripts/seed-screenshots.py http://127.0.0.1:8080/api/v1

会新建一个匿名账号，并造出：
- 3 个家庭（我的家 / 父母家 / 办公室），6 个房间跨家庭分布（3 / 2 / 1）；
- 12 台设备按 7 / 3 / 2 分到三个家庭（5 种 deviceType，其中 2 台只有 3 天前的读数）；
- 每台设备近 7 天的读数（覆盖心率/环境/体重等指标）；
- 4 个场景（指标阈值、离线、低 RSSI、定时），其中 1 个稍后由截图流程标成 rejected；
- 3 条反馈（其中 1 条已关闭）。
最后打印配对码，供 App「用配对码恢复原账号」接管这个账号。

脚本每次运行都会新建一个匿名账号，可重复执行；不修改任何已有账号的数据。
"""
import json
import random
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080/api/v1").rstrip("/")
random.seed(20261106)


def call(method, path, token=None, body=None, idem=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if idem:
        req.add_header("Idempotency-Key", idem)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        raise SystemExit(f"{method} {path} -> {e.code}: {raw[:400]}")


def rfc3339(dt):
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


now = datetime.now(timezone.utc)

_, auth = call("POST", "/auth/anonymous", body={})
TOKEN = auth["token"]
USER_ID = auth["userId"]

# 家庭：自有家庭改名 + 再建 2 个。房间跨家庭分布（3 / 2 / 1），
# 12 台设备按 7 / 3 / 2 分到三个家庭，首页家庭 Chip 才能体现真实结构。
_, households = call("GET", "/households", TOKEN)
own = households["items"][0]["id"]
HOUSEHOLD_ROOMS = {
    "我的家": ["客厅", "主卧", "次卧"],
    "父母家": ["厨房", "阳台"],
    "办公室": ["书房"],
}
HOUSEHOLD_IDS = {"我的家": own}
call("PATCH", f"/households/{own}", TOKEN, {"name": "我的家"})
for name in ("父母家", "办公室"):
    _, created = call("POST", "/households", TOKEN, {"name": name})
    HOUSEHOLD_IDS[name] = created["id"]

ROOM_IDS = {}
for household, rooms in HOUSEHOLD_ROOMS.items():
    ROOM_IDS[household] = {}
    for name in rooms:
        _, room = call("POST", f"/households/{HOUSEHOLD_IDS[household]}/rooms", TOKEN, {"name": name})
        ROOM_IDS[household][name] = room["id"]

# 12 台设备：类型来自 /catalog，分到 3 个家庭 / 6 个房间；后 2 台只有 3 天前的读数（演示「离线」）。
DEVICES = [
    # (externalId, deviceType, name, household, room, metrics)
    ("AA:BB:CC:00:01:01", "environmental_sensor", "客厅温湿度计", "我的家", "客厅", ["temperature_celsius", "relative_humidity", "pressure_hpa"]),
    ("AA:BB:CC:00:01:02", "heart_rate_monitor", "客厅心率带", "我的家", "客厅", ["heart_rate"]),
    ("AA:BB:CC:00:01:03", "environmental_sensor", "主卧温湿度计", "我的家", "主卧", ["temperature_celsius", "relative_humidity"]),
    ("AA:BB:CC:00:01:04", "smart_scale", "主卧体脂秤", "我的家", "主卧", ["weight_kg", "body_fat_percent", "bmi"]),
    ("AA:BB:CC:00:01:05", "environmental_sensor", "次卧温湿度计", "我的家", "次卧", ["temperature_celsius", "relative_humidity"]),
    ("AA:BB:CC:00:01:06", "heart_rate_monitor", "次卧心率带", "我的家", "次卧", ["heart_rate"]),
    ("AA:BB:CC:00:01:07", "environmental_sensor", "厨房温湿度计", "父母家", "厨房", ["temperature_celsius", "relative_humidity", "pressure_hpa"]),
    ("AA:BB:CC:00:01:08", "smart_scale", "厨房食材秤", "父母家", "厨房", ["weight_kg"]),
    ("AA:BB:CC:00:01:09", "cycling_speed_cadence", "客厅骑行台", "我的家", "客厅", ["cadence_rpm", "wheel_speed_kmh"]),
    ("AA:BB:CC:00:01:0A", "heart_rate_monitor", "书房心率带", "办公室", "书房", ["heart_rate"]),
    # 这两台只有 3 天前的数据，用来展示「长时间没有新读数」。
    ("AA:BB:CC:00:01:0B", "environmental_sensor", "阳台温湿度计", "父母家", "阳台", ["temperature_celsius", "relative_humidity"]),
    ("AA:BB:CC:00:01:0C", "environmental_sensor", "书房备用温湿度计", "办公室", "书房", ["temperature_celsius", "relative_humidity"]),
]
STALE = {"AA:BB:CC:00:01:0B", "AA:BB:CC:00:01:0C"}
# 额外推送一台「幽灵设备」并随后软删：它的场景引用会变成 deviceMissing，
# 而不影响上面 12 台可见设备。它不归属任何家庭。
GHOST = ("AA:BB:CC:00:01:0D", "environmental_sensor", "已移除的阳台备机", None, "阳台", ["temperature_celsius"])

METRIC_RANGES = {
    "temperature_celsius": (18.0, 30.0, "celsius"),
    "relative_humidity": (35.0, 65.0, "%"),
    "pressure_hpa": (995.0, 1020.0, "hectopascal"),
    "heart_rate": (58.0, 92.0, "bpm"),
    "weight_kg": (58.0, 72.0, "kg"),
    "body_fat_percent": (16.0, 26.0, "%"),
    "bmi": (19.0, 25.0, "kg/m2"),
    "cadence_rpm": (60.0, 95.0, "rpm"),
    "wheel_speed_kmh": (12.0, 28.0, "km/h"),
}

for mac, dtype, name, household, room, metrics in DEVICES + [GHOST]:
    call(
        "POST",
        "/sync/push",
        TOKEN,
        {
            "clientId": "seed-screen-device-" + mac,
            "devices": [
                {
                    "id": str(uuid.uuid4()),
                    "platform": "android",
                    "externalId": mac,
                    "mac": mac.lower(),
                    "alias": name,
                    "advertisedName": name,
                    "deviceType": dtype,
                    "group": room,
                    "updatedAt": rfc3339(now - timedelta(days=3 if mac in STALE else 0)),
                    "scanRecords": [
                        {
                            "rssi": -82 if mac in STALE else -60,
                            "at": rfc3339(now - timedelta(days=3) if mac in STALE else now),
                        }
                    ],
                }
            ],
        },
        idem="seed-screen-device-" + mac,
    )
    if household is not None:
        # 设备归属：家庭 + 房间。首页按 roomId 分组，未归属会落在「未分配」。
        call(
            "PATCH",
            f"/my/devices/android/{mac}",
            TOKEN,
            {
                "householdId": HOUSEHOLD_IDS[household],
                "roomId": ROOM_IDS[household][room],
            },
        )

# 读数：近 7 天，每天每指标 2 个点。
for mac, dtype, name, household, room, metrics in DEVICES:
    readings = []
    days = 7 if mac not in STALE else 3
    for day in range(days):
        for metric in metrics:
            low, high, unit = METRIC_RANGES[metric]
            for hour in (9, 19):
                value = round(random.uniform(low, high), 1)
                if metric in ("heart_rate", "cadence_rpm", "body_fat_percent", "pressure_hpa", "bmi"):
                    value = round(value)
                ts = now - timedelta(days=day, hours=hour)
                readings.append(
                    {
                        "id": str(uuid.uuid4()),
                        "platform": "android",
                        "externalId": mac,
                        "metric": metric,
                        "value": float(value),
                        "unit": unit,
                        "recordedAt": rfc3339(ts),
                    }
                )
    call(
        "POST",
        "/sync/push",
        TOKEN,
        {"clientId": "seed-screen-readings-" + mac, "readings": readings},
        idem="seed-screen-readings-" + mac,
    )

# 场景：4 个（指标 / 离线 / 低 RSSI / 定时）。
SCENES = [
    {
        "name": "客厅温度过高提醒",
        "enabled": True,
        "cooldownSeconds": 600,
        "trigger": {
            "kind": "metric_threshold",
            "device": {"platform": "android", "externalId": "AA:BB:CC:00:01:01"},
            "deviceType": "environmental_sensor",
            "metric": {
                "metric": "temperature_celsius",
                "comparator": "gte",
                "threshold": 28,
                "consecutive": 3,
            },
        },
        "actions": [
            {"kind": "notify", "title": "客厅温度 {value}°C", "body": "{device} 已超过 28°C，持续 3 次"},
            {"kind": "log", "message": "{device} 温度 {value}", "level": "warn"},
        ],
    },
    {
        "name": "主卧设备离线提醒",
        "enabled": True,
        "cooldownSeconds": 1800,
        "trigger": {
            "kind": "device_offline",
            "device": {"platform": "android", "externalId": "AA:BB:CC:00:01:03"},
            "offlineSeconds": 900,
        },
        "actions": [{"kind": "notify", "title": "{device} 离线", "body": "主卧设备已离线，请检查供电"}],
    },
    {
        "name": "阳台信号弱记录",
        "enabled": True,
        "cooldownSeconds": 120,
        "trigger": {
            "kind": "rssi_below",
            "device": {"platform": "android", "externalId": "AA:BB:CC:00:01:0D"},
            "rssiThreshold": -75,
        },
        "actions": [{"kind": "log", "message": "{device} 信号低于 -75 dBm", "level": "info"}],
    },
    {
        "name": "工作日早晨开窗提醒",
        "enabled": True,
        "cooldownSeconds": 3600,
        "trigger": {
            "kind": "schedule",
            "timeOfDay": "08:00",
            "timezone": "Asia/Shanghai",
            "daysOfWeek": [1, 2, 3, 4, 5],
        },
        "actions": [{"kind": "notify", "title": "早晨通风", "body": "今天工作日的 08:00，记得开窗 15 分钟"}],
    },
]
for scene in SCENES:
    call("POST", "/scenes", TOKEN, scene)

# 软删幽灵设备：让上面 rssi_below 场景的 deviceMissing 变为 true（场景本身保留）。
call(
    "POST",
    "/sync/push",
    TOKEN,
    {
        "clientId": "seed-screen-device-" + GHOST[0],
        "devices": [
            {
                "id": str(uuid.uuid4()),
                "platform": "android",
                "externalId": GHOST[0],
                "mac": GHOST[0].lower(),
                "deleted": True,
                "updatedAt": rfc3339(now + timedelta(minutes=1)),
            }
        ],
    },
    idem="seed-screen-device-delete-" + GHOST[0],
)

# 反馈：3 条，其中 1 条关闭。
FEEDBACKS = [
    {
        "kind": "bug",
        "body": "在 2.0 倍字号下，场景列表的冷却时间会被截断。",
        "contact": "tester@example.com",
        "context": {"platform": "android", "appVersion": "0.23.0", "buildType": "debug", "osVersion": "Android 16", "deviceModel": "Pixel 8"},
        "diagnostics": "fontScale=2.0; scene row width=1080",
    },
    {
        "kind": "feature",
        "body": "希望场景支持按房间批量启停。",
        "contact": "tester@example.com",
        "context": {"platform": "android", "appVersion": "0.23.0", "buildType": "debug", "osVersion": "Android 16", "deviceModel": "Pixel 8"},
    },
    {
        "kind": "experience",
        "body": "市场页的配件说明太长了，建议折叠到详情里。",
        "contact": "tester@example.com",
        "context": {"platform": "android", "appVersion": "0.23.0", "buildType": "debug", "osVersion": "Android 16", "deviceModel": "Pixel 8"},
    },
]
feedback_ids = []
for fb in FEEDBACKS:
    _, created = call("POST", "/feedback", TOKEN, fb, idem="seed-screen-feedback-" + str(uuid.uuid4()))
    feedback_ids.append(created["feedbackId"])
_, closed = call("PATCH", f"/feedback/{feedback_ids[0]}", TOKEN, {"status": "closed"})
assert closed["status"] == "closed"

# 配对码：App 用它在「我的 → 用配对码恢复原账号」接管这个账号。
_, pairing = call("POST", "/auth/pairing-code", TOKEN, {})
code = pairing["code"]

env = "\n".join(
    [
        f"BASE_URL={BASE}",
        f"USER_ID={USER_ID}",
        f"TOKEN={TOKEN}",
        f"PAIRING_CODE={code}",
        f"FEEDBACK_CLOSED_ID={feedback_ids[0]}",
    ]
)
with open("/tmp/screenshot-seed.env", "w") as f:
    f.write(env + "\n")
print(env)
print("seeded: 3 households / 6 rooms / 12 devices / 4 scenes / 3 feedbacks")
