#!/usr/bin/env python3
"""把演示用的本地读数与扫描日志写进 App 的 Room 数据库（仅 debug 包可用）。

服务端 seed（scripts/seed-screenshots.py）负责账号/家庭/设备/场景/反馈与云端读数；
首页「今日概览」、离线兜底图和「扫描日志」都读本地 Room，所以截图前再跑一次本脚本：

  python3 scripts/seed-local-db.py [--serial emulator-5554] [--package com.platojobs.pkotlinapp]

- 读数：每台设备近 7 天；温度/湿度/气压/心率每 10 分钟一条原始读数
  （聚合到 30 分钟 / 1 小时桶后仍有 min–max 形状），电量每小时一条；
  两个离线设备的最新读数停在 3 天前，与设备状态一致。
- 扫描日志：12 条，覆盖在线/离线设备，RSSI 在 -45 ~ -88 之间，时间落在最近 2 小时。
- 可重复执行：读数按 `seed-local-` 前缀替换，扫描日志按演示设备 mac 先删后插；
  不会动真实采集的数据。
"""
import argparse
import math
import os
import random
import sqlite3
import subprocess
import sys
import tempfile
import time

DB_PATH = "databases/device_dashboard.db"
LOCAL_READING_PREFIX = "seed-local-"
# 按设备名匹配：MAC 由 seed-screenshots.py 随机生成，脚本不复制一份易漂移的 MAC 常量。
STALE_NAMES = {"阳台温湿度计", "书房备用温湿度计"}
REJECTED_SCENE_NAME = "主卧设备离线提醒"
# 与 scripts/seed-screenshots.py 的设备/房间归属保持一致。
DEVICE_ROOM = {
    "客厅温湿度计": "客厅",
    "客厅心率带": "客厅",
    "主卧温湿度计": "主卧",
    "主卧体脂秤": "主卧",
    "次卧温湿度计": "次卧",
    "次卧心率带": "次卧",
    "厨房温湿度计": "厨房",
    "厨房食材秤": "厨房",
    "客厅骑行台": "客厅",
    "书房心率带": "书房",
    "阳台温湿度计": "阳台",
    "书房备用温湿度计": "书房",
}

DENSE_STEP_MIN = 10
STEP_MIN = DENSE_STEP_MIN
DAYS = 7

UNIT = {
    "temperature": ("°C", 1),
    "humidity": ("%", 1),
    "pressure": ("hPa", 0),
    "heart_rate": ("bpm", 0),
    "battery": ("%", 1),
}

METRICS_BY_TYPE = {
    "environmental_sensor": ["temperature", "humidity", "pressure"],
    "heart_rate_monitor": ["heart_rate"],
    "smart_scale": [],
    "cycling_speed_cadence": [],
}


def run(*args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, **kwargs).stdout


def pull_db(serial, package, target):
    adb = ["adb", "-s", serial] if serial else ["adb"]
    run(*adb, "shell", "am", "force-stop", package)
    # Room 默认 WAL：只拷主库会漏掉尚未 checkpoint 的数据，-wal/-shm 一起拉。
    for suffix in ("", "-wal", "-shm"):
        path = target + suffix
        with open(path, "wb") as f:
            try:
                f.write(run(*adb, "exec-out", "run-as", package, "cat", DB_PATH + suffix))
            except subprocess.CalledProcessError:
                pass
        if suffix and os.path.getsize(path) == 0:
            os.remove(path)


def push_db(serial, package, source):
    adb = ["adb", "-s", serial] if serial else ["adb"]
    run(*adb, "push", source, "/data/local/tmp/seed-local.db")
    # 不走 sh -c：adb shell 会重新拼接参数把引号吃掉；run-as 可直接执行 cp/rm。
    run(*adb, "shell", "run-as", package, "cp",
        "/data/local/tmp/seed-local.db", DB_PATH)
    run(*adb, "shell", "run-as", package, "rm", "-f",
        DB_PATH + "-wal", DB_PATH + "-shm")
    run(*adb, "shell", "rm", "-f", "/data/local/tmp/seed-local.db")


def value_for(metric, ts_ms, phase):
    day_phase = 2 * math.pi * (ts_ms / 86_400_000.0) + phase
    noise = random.gauss(0.0, 1.0)
    spike = 2.0 if random.random() < 0.02 else 0.0
    if metric == "temperature":
        value = 24.0 + 5.0 * math.sin(day_phase) + 0.35 * noise + spike
    elif metric == "humidity":
        value = 52.0 + 10.0 * math.sin(day_phase + 0.8) + 1.0 * noise + spike * 2.0
    elif metric == "pressure":
        value = 1008.0 + 3.0 * math.sin(day_phase / 3.0) + 0.5 * noise
    elif metric == "heart_rate":
        value = 74.0 + 7.0 * math.sin(day_phase) + 2.0 * noise + spike * 9.0
    elif metric == "battery":
        days_ago = max(0.0, (time.time() * 1000.0 - ts_ms) / 86_400_000.0)
        value = 96.0 - 0.9 * days_ago + 0.25 * noise
    else:
        value = 0.0
    if metric == "temperature":
        return min(30.0, max(18.0, value))
    if metric == "humidity":
        return min(65.0, max(35.0, value))
    if metric == "pressure":
        return min(1020.0, max(995.0, value))
    if metric == "heart_rate":
        return min(92.0, max(58.0, value))
    return min(100.0, max(20.0, value))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default=None)
    parser.add_argument("--package", default="com.platojobs.pkotlinapp")
    args = parser.parse_args()

    random.seed(20261106)
    now_ms = int(time.time() * 1000)
    aligned = now_ms - now_ms % (STEP_MIN * 60_000)

    with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as tmp:
        db_file = tmp.name
    pull_db(args.serial, args.package, db_file)

    con = sqlite3.connect(db_file)
    cur = con.cursor()
    cur.execute(f"DELETE FROM readings WHERE id LIKE '{LOCAL_READING_PREFIX}%'")

    devices = list(cur.execute("SELECT mac, name, deviceType FROM devices"))
    # 真实 BLE 扫描会写 lastSeen/rssi，房间归属由服务端同步；这里按演示账号补齐，
    # 首页的在线/离线与「未分配房间」统计才和 seed 的服务端结构一致。
    rooms = {
        name: (room_id, household_id, remote_id)
        for room_id, name, household_id, remote_id in cur.execute(
            "SELECT id, name, householdId, remoteId FROM rooms"
        )
    }
    for index, (mac, name, _) in enumerate(devices):
        room = DEVICE_ROOM.get(name)
        room_id, household_id, remote_room_id = rooms.get(room, (None, None, None)) \
            if room else (None, None, None)
        seen = now_ms - 3 * 86_400_000 if name in STALE_NAMES else now_ms - (index % 5) * 60_000
        cur.execute(
            "UPDATE devices SET lastSeenTimestamp = ?, lastRssi = ?, roomId = ?, "
            "remoteRoomId = ?, householdId = ? WHERE mac = ?",
            (
                seen,
                -82 if name in STALE_NAMES else -55 - (index % 4) * 7,
                room_id,
                remote_room_id,
                household_id,
                mac,
            ),
        )
    # scan_logs 的 id 是自增整数，只能按 mac 清理；这 12 台是本脚本账号的演示设备。
    if devices:
        cur.executemany(
            "DELETE FROM scan_logs WHERE mac = ?",
            [(mac,) for mac, _, _ in devices],
        )
    reading_count = 0
    for mac, name, dtype in devices:
        end = aligned - 3 * 86_400_000 if name in STALE_NAMES else aligned
        metrics = METRICS_BY_TYPE.get(dtype, [])
        # 电量对每台设备都造，但低频（每小时），避免行数无谓膨胀。
        streams = [(m, STEP_MIN) for m in metrics] + [("battery", 60)]
        for metric, step in streams:
            unit, digits = UNIT[metric]
            phase = (hash((mac, metric)) % 1000) / 1000.0 * 2 * math.pi
            count = int(DAYS * 24 * 60 / step)
            rows = []
            for index in range(count):
                ts = end - step * 60_000 * index
                value = value_for(metric, ts, phase)
                value = round(value, digits)
                rows.append(
                    (
                        f"{LOCAL_READING_PREFIX}{mac}-{metric}-{ts}",
                        mac,
                        metric,
                        value,
                        unit,
                        ts,
                        "synced",
                        None,
                    )
                )
            cur.executemany(
                "INSERT OR REPLACE INTO readings "
                "(id, deviceId, metric, value, unit, recordedAt, syncState, rawSnapshot) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                rows,
            )
            reading_count += len(rows)

    scan_rows = []
    for index, (mac, name, _) in enumerate(devices[:12]):
        scan_rows.append(
            (
                mac,
                name,
                -86 if name in STALE_NAMES else -48 - (index % 5) * 8,
                now_ms - (index + 1) * 7 * 60_000,
            )
        )
    cur.executemany(
        "INSERT INTO scan_logs (mac, name, rssi, timestamp) VALUES (?, ?, ?, ?)",
        scan_rows,
    )

    # 场景页要演示服务端终态拒绝：标记一个场景为 rejected；本地编辑时间晚于
    # serverUpdatedAt，下一次同步才不会把远端版本覆盖回来。
    cur.execute(
        "UPDATE scenes SET syncState = 'synced', syncError = NULL "
        "WHERE syncState = 'rejected'",
    )
    cur.execute(
        "UPDATE scenes SET syncState = 'rejected', "
        "syncError = 'SCENE_ACTION_INVALID', "
        "actionsUpdatedAt = MAX(actionsUpdatedAt, serverUpdatedAt + 1000), "
        "triggerUpdatedAt = MAX(triggerUpdatedAt, serverUpdatedAt + 1000) "
        "WHERE name = ?",
        (REJECTED_SCENE_NAME,),
    )
    rejected_scenes = cur.execute(
        "SELECT COUNT(*) FROM scenes WHERE syncState = 'rejected'"
    ).fetchone()[0]

    con.commit()
    # checkpoint 需要独占锁：必须先提交事务，否则会报 table is locked。
    cur.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.close()

    push_db(args.serial, args.package, db_file)
    print(
        f"local seed: {reading_count} readings, {len(scan_rows)} scan logs, "
        f"{rejected_scenes} rejected scene(s), {len(devices)} devices"
    )


if __name__ == "__main__":
    sys.exit(main())
