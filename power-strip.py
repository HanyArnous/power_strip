#!/usr/bin/env python3
"""power-strip - local control for the LG U+ / Jinheung MTTL-W01 4-outlet smart strip.

Stdlib only, one file, three commands:

  python power-strip.py serve       TCP 10086 device server + web UI on 8080  (default)
  python power-strip.py provision   point the strip at this PC (while in SoftAP setup mode)
  python power-strip.py selftest    offline protocol check

The strip speaks plain text lines ("up:...") over TCP 10086. It connects to this
PC directly because provisioning stores the PC's IP in the strip. No cloud, no
router DNS or DNAT change, no MQTT, no Home Assistant.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import ipaddress
import json
import re
import secrets
import socket
import sqlite3
import sys
import tempfile
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

DEVICE_PORT = 10086
SETUP_HOST = "192.168.1.1"      # strip SoftAP setup service
SETUP_PORT = 30300
WEB_PORT = 8080
POLL_SECONDS = 5.0
DIAG_EVERY = 3                  # voltage/current/RSSI every 3rd poll

BOOTINFO_RE = re.compile(
    r"^up:bootinfo:([^;\r\n]+);([0-9A-Fa-f]{12});([0-9A-Fa-f]{12});([^;\r\n]+);connect$"
)
GETINFO_RE = re.compile(
    r"(?P<ch>[1-5]):(?P<runtime>-?\d+);(?P<relay>on|off);(?P<state>-?\d+);"
    r"(?P<overload>[^;:]+);(?P<overheat>[^;:]+);(?P<power>-?\d+);"
    r"(?P<energy>[0-9A-Fa-f]{8});(?P<previous>[0-9A-Fa-f]{8});"
    r"(?P<config>[0-9A-Fa-f]{8});(?P<status>[^;:]+);"
    r"(?P<event>[0-9A-Fa-f]{2});(?P<temperature>-?\d+)",
    re.I,
)
ONOFF_ACK_RE = re.compile(r"^up:onoff:([1-4]):(on|off)$", re.I)
EVENT_RE = re.compile(r"^up:event:onoff:([0-4]):(on|off)$", re.I)
POWER_REPORT_RE = re.compile(r"^up:power_report:([1-5]):(-?\d+)$", re.I)
QUERY_RE = re.compile(r"^up:query:(-?\d+)$")
MAC_RE = re.compile(r"^[0-9A-F]{12}$")

DB_NAME = "power-strip.db"
NAME_STRIP_MAX = 40
NAME_OUTLET_MAX = 24
SAMPLE_EVERY = 60.0
SAMPLE_RETENTION = 30 * 86400
HISTORY_POINT_LIMIT = 5000
SCENE_NAME_MAX = 40
SCENE_WATTS_MAX = 5000.0
SCENE_FOR_S_MAX = 3600.0
SCENE_COOLDOWN = 60.0


def clean_name(value: object, limit: int) -> str:
    return str(value or "").strip().replace("\r", " ").replace("\n", " ")[:limit]


class NameStore:
    """Custom strip/outlet names in sqlite (stdlib only). Thread-safe via a lock."""

    def __init__(self, path: str | Path | None = None):
        self.path = str(path or (Path(__file__).resolve().parent / DB_NAME))
        self._lock = threading.Lock()
        with self._lock, self._connect() as con:
            con.execute(
                "CREATE TABLE IF NOT EXISTS names (mac TEXT PRIMARY KEY, strip TEXT NOT NULL DEFAULT '',"
                " o1 TEXT NOT NULL DEFAULT '', o2 TEXT NOT NULL DEFAULT '',"
                " o3 TEXT NOT NULL DEFAULT '', o4 TEXT NOT NULL DEFAULT '')"
            )

    def _connect(self) -> sqlite3.Connection:
        con = sqlite3.connect(self.path, timeout=10.0)
        con.execute("PRAGMA journal_mode=WAL")
        return con

    def all(self) -> dict:
        with self._lock, self._connect() as con:
            rows = con.execute("SELECT mac,strip,o1,o2,o3,o4 FROM names").fetchall()
        return {mac: {"strip": strip, "outlets": {n: v for n, v in enumerate((o1, o2, o3, o4), 1) if v}}
                for mac, strip, o1, o2, o3, o4 in rows}

    def get(self, mac: str) -> dict:
        with self._lock, self._connect() as con:
            row = con.execute(
                "SELECT strip,o1,o2,o3,o4 FROM names WHERE mac=?", (mac.upper(),)).fetchone()
        if not row:
            return {"strip": "", "outlets": {}}
        return {"strip": row[0], "outlets": {n: v for n, v in enumerate(row[1:], 1) if v}}

    def set(self, mac: str, strip: object = None, outlets: dict | None = None) -> dict:
        mac = str(mac or "").upper()
        if not MAC_RE.fullmatch(mac):
            raise ValueError(f"bad mac {mac!r}")
        with self._lock, self._connect() as con:
            row = con.execute(
                "SELECT strip,o1,o2,o3,o4 FROM names WHERE mac=?", (mac,)).fetchone()
            cur: dict = {"strip": row[0] if row else ""}
            for n in range(1, 5):
                cur[n] = row[n] if row else ""
            if strip is not None:
                cur["strip"] = clean_name(strip, NAME_STRIP_MAX)
            for k, v in (outlets or {}).items():
                try:
                    n = int(k)
                except (TypeError, ValueError):
                    continue
                if 1 <= n <= 4:
                    cur[n] = clean_name(v, NAME_OUTLET_MAX)
            con.execute(
                "INSERT INTO names (mac,strip,o1,o2,o3,o4) VALUES (?,?,?,?,?,?)"
                " ON CONFLICT(mac) DO UPDATE SET strip=excluded.strip,o1=excluded.o1,"
                " o2=excluded.o2,o3=excluded.o3,o4=excluded.o4",
                (mac, cur["strip"], cur[1], cur[2], cur[3], cur[4]))
        return {"strip": cur["strip"], "outlets": {n: cur[n] for n in range(1, 5) if cur[n]}}


def compute_report(rows_by_outlet: dict, from_ts: float, to_ts: float) -> dict:
    """Pure aggregation over {outlet: [(ts, power_w, energy_kwh), ...]} (ascending ts).

    kWh comes from positive energy-counter deltas (a drop means the strip
    rebooted its counter, so only the fresh value counts from there).
    """
    per, total = {}, 0.0
    peak = {"outlet": 0, "power_w": 0.0, "ts": None}
    for n in (1, 2, 3, 4):
        rows = [r for r in rows_by_outlet.get(n, []) if from_ts <= r[0] <= to_ts]
        if not rows:
            per[n] = {"kwh": 0.0, "max_w": 0.0, "max_t": None, "min_w": 0.0, "avg_w": 0.0, "samples": 0}
            continue
        kwh, prev = 0.0, None
        max_w, max_t, min_w, acc = -1.0, None, None, 0.0
        for ts, power, energy in rows:
            if prev is not None and energy is not None:
                kwh += max(0.0, energy - prev)
            prev = energy
            if power is not None:
                acc += power
                if power > max_w:
                    max_w, max_t = power, int(ts)
                if min_w is None or power < min_w:
                    min_w = power
        kwh = round(kwh, 3)
        total += kwh
        per[n] = {"kwh": kwh, "max_w": round(max_w, 2), "max_t": max_t,
                  "min_w": round(min_w if min_w is not None else 0.0, 2),
                  "avg_w": round(acc / len(rows), 2), "samples": len(rows)}
        if max_w > peak["power_w"]:
            peak = {"outlet": n, "power_w": round(max_w, 2), "ts": max_t}
    return {"from": int(from_ts), "to": int(to_ts), "total_kwh": round(total, 3),
            "outlets": per, "peak": peak}


class HistoryStore:
    """Energy time-series in sqlite (stdlib). One row per outlet per sample."""

    def __init__(self, path: str | Path | None = None):
        self.path = str(path or (Path(__file__).resolve().parent / DB_NAME))
        self._lock = threading.Lock()
        with self._lock, self._connect() as con:
            con.execute(
                "CREATE TABLE IF NOT EXISTS samples (mac TEXT NOT NULL, outlet INTEGER NOT NULL,"
                " ts INTEGER NOT NULL, power_w REAL, energy_kwh REAL, temp_c INTEGER, voltage REAL,"
                " PRIMARY KEY (mac, outlet, ts))")

    def _connect(self) -> sqlite3.Connection:
        con = sqlite3.connect(self.path, timeout=10.0)
        con.execute("PRAGMA journal_mode=WAL")
        return con

    def record_snapshot(self, snap: dict, ts: float) -> int:
        rows = []
        for d in snap.get("devices", []):
            if not d.get("online"):
                continue
            for o in d.get("outlets", []):
                rows.append((d["mac"], int(o["n"]), int(ts), o.get("power_w"),
                             o.get("energy_kwh"), o.get("temp_c"), d.get("voltage")))
        if not rows:
            return 0
        with self._lock, self._connect() as con:
            con.executemany(
                "INSERT OR IGNORE INTO samples (mac,outlet,ts,power_w,energy_kwh,temp_c,voltage)"
                " VALUES (?,?,?,?,?,?,?)", rows)
        return len(rows)

    def prune(self, before_ts: float) -> int:
        with self._lock, self._connect() as con:
            cur = con.execute("DELETE FROM samples WHERE ts < ?", (int(before_ts),))
            return cur.rowcount

    def query(self, mac: str, outlet: int, from_ts: float, to_ts: float,
              bucket: str = "raw", limit: int = HISTORY_POINT_LIMIT) -> list:
        mac = mac.upper()
        with self._lock, self._connect() as con:
            if bucket == "raw":
                rows = con.execute(
                    "SELECT ts,power_w,energy_kwh,temp_c,voltage FROM samples"
                    " WHERE mac=? AND (?=0 OR outlet=?) AND ts BETWEEN ? AND ?"
                    " ORDER BY ts LIMIT ?", (mac, outlet, outlet, int(from_ts), int(to_ts), limit)).fetchall()
                return [{"t": ts, "power_w": p, "energy_kwh": e, "temp_c": t, "voltage": v}
                        for ts, p, e, t, v in rows]
            step = 3600 if bucket == "hour" else 86400
            rows = con.execute(
                "SELECT (ts/?)*? AS b, AVG(power_w), MAX(power_w), MIN(energy_kwh), MAX(energy_kwh)"
                " FROM samples WHERE mac=? AND (?=0 OR outlet=?) AND ts BETWEEN ? AND ?"
                " GROUP BY b ORDER BY b LIMIT ?",
                (step, step, mac, outlet, outlet, int(from_ts), int(to_ts), limit)).fetchall()
            out = []
            for b, avg_p, max_p, emin, emax in rows:
                out.append({"t": b, "power_w": round(avg_p or 0.0, 2), "max_w": max_p,
                            "energy_kwh": round(max(0.0, (emax or 0.0) - (emin or 0.0)), 3)})
            return out

    def rows_for_report(self, mac: str, from_ts: float, to_ts: float, bucket: str) -> dict:
        """{outlet: [(ts, power_w, energy_kwh)]} using raw or hour buckets."""
        mac = mac.upper()
        with self._lock, self._connect() as con:
            if bucket == "raw":
                rows = con.execute(
                    "SELECT outlet,ts,power_w,energy_kwh FROM samples"
                    " WHERE mac=? AND ts BETWEEN ? AND ? ORDER BY outlet,ts",
                    (mac, int(from_ts), int(to_ts))).fetchall()
                out: dict = {}
                for n, ts, p, e in rows:
                    out.setdefault(n, []).append((ts, p, e))
                return out
            step = 3600
            rows = con.execute(
                "SELECT outlet,(ts/?)*? AS b,AVG(power_w),MAX(energy_kwh) FROM samples"
                " WHERE mac=? AND ts BETWEEN ? AND ? GROUP BY outlet,b ORDER BY outlet,b",
                (step, step, mac, int(from_ts), int(to_ts))).fetchall()
            out = {}
            for n, b, p, e in rows:
                out.setdefault(n, []).append((b, p, e))
            return out


def report_window(period: str, now: float | None = None) -> tuple[float, float, str]:
    """Local-time window for today / last 7 / last 30 days. Returns (from, to, bucket)."""
    now = now if now is not None else time.time()
    midnight = datetime.fromtimestamp(now).replace(hour=0, minute=0, second=0, microsecond=0).timestamp()
    if period == "week":
        return midnight - 6 * 86400, now, "hour"
    if period == "month":
        return midnight - 29 * 86400, now, "hour"
    return midnight, now, "raw"


def _scene_outlet(value: object) -> int:
    n = int(value)
    if n not in (0, 1, 2, 3, 4):
        raise ValueError("outlet must be 0-4 (0 = all)")
    return n


def validate_scene(spec: object) -> dict:
    """Validate a scene body, return normalized {name, enabled, trigger, actions}."""
    if not isinstance(spec, dict):
        raise ValueError("scene must be an object")
    name = clean_name(spec.get("name", ""), SCENE_NAME_MAX)
    if not name:
        raise ValueError("name required")
    trig = spec.get("trigger")
    if not isinstance(trig, dict):
        raise ValueError("trigger required")
    ttype = trig.get("type")
    if ttype == "threshold":
        mac = str(trig.get("mac") or "").upper()
        if not MAC_RE.fullmatch(mac):
            raise ValueError("bad trigger mac")
        try:
            outlet = int(trig.get("outlet"))
        except (TypeError, ValueError):
            raise ValueError("trigger outlet must be 1-4")
        if outlet not in (1, 2, 3, 4):
            raise ValueError("trigger outlet must be 1-4")
        direction = trig.get("direction")
        if direction not in ("above", "below"):
            raise ValueError("direction must be above|below")
        try:
            watts = float(trig.get("watts"))
        except (TypeError, ValueError):
            raise ValueError("watts must be a number")
        if not 0 <= watts <= SCENE_WATTS_MAX:
            raise ValueError(f"watts must be 0-{SCENE_WATTS_MAX:g}")
        try:
            for_s = float(trig.get("for_s", 10))
        except (TypeError, ValueError):
            raise ValueError("for_s must be seconds")
        if not 0 <= for_s <= SCENE_FOR_S_MAX:
            raise ValueError(f"for_s must be 0-{SCENE_FOR_S_MAX:g}")
        trigger = {"type": "threshold", "mac": mac, "outlet": outlet,
                   "direction": direction, "watts": watts, "for_s": for_s}
    elif ttype == "schedule":
        when = str(trig.get("time") or "")
        if not re.fullmatch(r"([01]\d|2[0-3]):[0-5]\d", when):
            raise ValueError("time must be HH:MM (24h)")
        days = trig.get("days", [0, 1, 2, 3, 4, 5, 6])
        if not isinstance(days, list) or not days or any(d not in (0, 1, 2, 3, 4, 5, 6) for d in days):
            raise ValueError("days must be a non-empty list of 0-6 (Mon=0)")
        trigger = {"type": "schedule", "time": when, "days": sorted(set(days))}
    elif ttype == "manual":
        trigger = {"type": "manual"}
    else:
        raise ValueError("trigger.type must be threshold|schedule|manual")
    actions = spec.get("actions")
    if not isinstance(actions, list) or not actions:
        raise ValueError("at least one action required")
    norm = []
    for a in actions:
        if not isinstance(a, dict):
            raise ValueError("each action must be an object")
        mac = str(a.get("mac") or "").upper()
        if not MAC_RE.fullmatch(mac):
            raise ValueError("bad action mac")
        norm.append({"mac": mac, "outlet": _scene_outlet(a.get("outlet")), "on": bool(a.get("on"))})
    return {"name": name, "enabled": bool(spec.get("enabled", True)),
            "trigger": trigger, "actions": norm}


class SceneStore:
    """Automation scenes in sqlite (stdlib). spec holds name/enabled/trigger/actions."""

    def __init__(self, path: str | Path | None = None):
        self.path = str(path or (Path(__file__).resolve().parent / DB_NAME))
        self._lock = threading.Lock()
        with self._lock, self._connect() as con:
            con.execute(
                "CREATE TABLE IF NOT EXISTS scenes (id TEXT PRIMARY KEY,"
                " spec TEXT NOT NULL, last_fired REAL NOT NULL DEFAULT 0)")

    def _connect(self) -> sqlite3.Connection:
        con = sqlite3.connect(self.path, timeout=10.0)
        con.execute("PRAGMA journal_mode=WAL")
        return con

    def all(self) -> list:
        with self._lock, self._connect() as con:
            rows = con.execute("SELECT id,spec,last_fired FROM scenes ORDER BY rowid").fetchall()
        out = []
        for sid, spec, last in rows:
            try:
                out.append({"id": sid, **json.loads(spec), "last_fired": last or None})
            except (ValueError, TypeError):
                continue
        return out

    def get(self, sid: str) -> dict | None:
        with self._lock, self._connect() as con:
            row = con.execute("SELECT spec,last_fired FROM scenes WHERE id=?", (sid,)).fetchone()
        if not row:
            return None
        return {"id": sid, **json.loads(row[0]), "last_fired": row[1] or None}

    def create(self, spec: object) -> dict:
        norm = validate_scene(spec)
        sid = secrets.token_hex(6)
        with self._lock, self._connect() as con:
            con.execute("INSERT INTO scenes (id,spec,last_fired) VALUES (?,?,0)", (sid, json.dumps(norm)))
        return {"id": sid, **norm, "last_fired": None}

    def update(self, sid: str, spec: object) -> dict | None:
        norm = validate_scene(spec)
        with self._lock, self._connect() as con:
            cur = con.execute("UPDATE scenes SET spec=? WHERE id=?", (json.dumps(norm), sid))
            if not cur.rowcount:
                return None
            last = con.execute("SELECT last_fired FROM scenes WHERE id=?", (sid,)).fetchone()
        return {"id": sid, **norm, "last_fired": (last[0] or None) if last else None}

    def delete(self, sid: str) -> bool:
        with self._lock, self._connect() as con:
            return con.execute("DELETE FROM scenes WHERE id=?", (sid,)).rowcount > 0

    def mark_fired(self, sid: str, ts: float) -> None:
        with self._lock, self._connect() as con:
            con.execute("UPDATE scenes SET last_fired=? WHERE id=?", (ts, sid))


def parse_getinfo(line: str) -> list[dict]:
    """Parse 'up:getinfo:...' into per-outlet records (channel 5 is the aggregate)."""
    text = line.strip("\r\n\x00 ")
    if text.startswith("up:getinfo:"):
        text = text[len("up:getinfo:"):]
    out = []
    for m in GETINFO_RE.finditer(text):
        g = m.groupdict()
        ch = int(g["ch"])
        if ch > 4:
            continue
        out.append({
            "n": ch,
            "on": g["relay"].lower() == "on",
            "power_w": round(int(g["power"]) / 1000.0, 2),
            "energy_kwh": round(int(g["energy"], 16) / 1000.0, 3),
            "temp_c": int(g["temperature"]),
        })
    return out


class Device:
    def __init__(self, mac: str, model: str, fw: str, ip: str):
        self.mac = mac.upper()
        self.model = model
        self.fw = fw
        self.ip = ip
        self.online = False
        self.voltage_v: float | None = None
        self.rssi: int | None = None
        self.outlets = {n: {"n": n, "on": False, "power_w": 0.0, "energy_kwh": 0.0, "temp_c": 0} for n in range(1, 5)}

    @property
    def name(self) -> str:
        return f"MTTL {self.mac[-7:]}"

    @property
    def current_a(self) -> float:
        if not self.voltage_v:
            return 0.0
        return round(sum(o["power_w"] for o in self.outlets.values()) / self.voltage_v, 2)

    def snapshot(self, names: dict | None = None) -> dict:
        names = names or {}
        strip_name = names.get("strip") or self.name
        custom = names.get("outlets", {})
        outlets = [dict(o, name=(custom.get(o["n"]) or f"Outlet {o['n']}"))
                   for o in self.outlets.values()]
        return {
            "mac": self.mac,
            "name": strip_name,
            "model": self.model,
            "fw": self.fw,
            "ip": self.ip,
            "online": self.online,
            "on": any(o["on"] for o in outlets),
            "power_w": round(sum(o["power_w"] for o in outlets), 2),
            "energy_kwh": round(sum(o["energy_kwh"] for o in outlets), 3),
            "voltage": self.voltage_v,
            "current_a": self.current_a,
            "rssi": self.rssi,
            "outlets": outlets,
        }


class Session:
    """One connected strip. Only one command transaction runs at a time."""

    def __init__(self, ip: str, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        self.ip = ip
        self.reader = reader
        self.writer = writer
        self.lock = asyncio.Lock()
        self.device: Device | None = None
        self.got_getinfo = asyncio.Event()
        self.last_cmd = ""
        self.last_rx = ""

    async def send(self, cmd: str) -> None:
        self.last_cmd = cmd
        self.writer.write((cmd + "\r\n").encode("ascii"))
        await self.writer.drain()

    async def _refresh(self, timeout: float = 6.0) -> bool:
        self.got_getinfo.clear()
        await self.send("up:getinfo:all")
        try:
            await asyncio.wait_for(self.got_getinfo.wait(), timeout)
            return True
        except asyncio.TimeoutError:
            return False

    async def refresh(self, timeout: float = 4.0) -> bool:
        async with self.lock:
            return await self._refresh(timeout)

    async def set_outlets(self, channels: list[int], on: bool) -> bool:
        async with self.lock:
            for ch in channels:
                await self.send(f"up:onoff:{ch}:{'on' if on else 'off'}")
            await asyncio.sleep(0.3)          # let the relay settle before the snapshot
            return await self._refresh()

    async def diagnostics(self) -> None:
        async with self.lock:
            await self.send("up:power_report:1:vol")
            await self.send("up:query:wifirssi")


async def _close_writer(writer: asyncio.StreamWriter) -> None:
    try:
        await writer.wait_closed()
    except Exception:
        pass


class Hub:
    def __init__(self, store: NameStore | None = None, history: HistoryStore | None = None,
                 scenes: SceneStore | None = None, sample_every: float = SAMPLE_EVERY) -> None:
        self.devices: dict[str, Device] = {}
        self.sessions: dict[str, Session] = {}
        self.store = store or NameStore()
        self.history = history or HistoryStore(self.store.path)
        self.scenes = scenes or SceneStore(self.store.path)
        self.sample_every = sample_every
        self._last_sample = 0.0
        self._scene_state: dict[str, dict] = {}

    def session(self, mac: str) -> Session | None:
        s = self.sessions.get(mac.upper())
        if s and s.device and s.device.online and not s.writer.is_closing():
            return s
        return None

    def snapshot(self) -> dict:
        return {"devices": [d.snapshot(self.store.get(d.mac)) for d in self.devices.values()]}

    async def handle_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        peer = writer.get_extra_info("peername")
        ip = peer[0] if peer else "?"
        s = Session(ip, reader, writer)
        print(f"[device] connection from {ip}")
        try:
            while True:
                raw = await reader.readline()
                if not raw:
                    break
                if len(raw) > 8192:
                    raise ValueError("line too long")
                line = raw.replace(b"\x00", b"").decode("utf-8", "replace").strip("\r\n ")
                s.last_rx = line

                m = BOOTINFO_RE.match(line)
                if m:
                    mac = m.group(2).upper()
                    d = self.devices.get(mac) or Device(mac, m.group(1), m.group(4), ip)
                    d.model, d.fw, d.ip, d.online = m.group(1), m.group(4), ip, True
                    s.device = d
                    self.devices[mac] = d
                    old = self.sessions.get(mac)
                    if old and old is not s:
                        old.writer.close()
                        asyncio.get_running_loop().create_task(_close_writer(old.writer))
                    self.sessions[mac] = s
                    print(f"[device] {d.name} model={d.model} fw={d.fw}")
                    asyncio.create_task(s.refresh())
                    continue

                if not s.device:
                    continue

                if line.startswith("up:getinfo:"):
                    records = parse_getinfo(line)
                    if len(records) == 4:
                        s.device.outlets = {r["n"]: r for r in records}
                        s.got_getinfo.set()
                    else:
                        print(f"[device] {s.device.name} getinfo: got {len(records)}/4 outlets, ignored")
                    continue

                if line.lower().startswith("up:power_report:"):
                    m = POWER_REPORT_RE.fullmatch(line)
                    if m and int(m.group(2)) >= 50000:      # mV -> V; sub-50000 values are per-outlet current, unused
                        s.device.voltage_v = round(int(m.group(2)) / 1000.0, 1)
                    continue

                m = QUERY_RE.fullmatch(line)
                if m:
                    s.device.rssi = int(m.group(1))
                    continue

                m = EVENT_RE.fullmatch(line)
                if m:
                    ch, on = int(m.group(1)), m.group(2).lower() == "on"
                    if ch == 0:
                        for outlet in s.device.outlets.values():
                            outlet["on"] = on
                    else:
                        s.device.outlets[ch]["on"] = on
                    continue

                if ONOFF_ACK_RE.fullmatch(line):
                    continue
        except (ConnectionResetError, BrokenPipeError, asyncio.IncompleteReadError):
            pass
        except Exception as err:
            print(f"[device] session error {ip}: {err!r} (last sent {s.last_cmd!r}, last received {s.last_rx!r})")
        finally:
            if s.device:
                if self.sessions.get(s.device.mac) is s:
                    self.sessions.pop(s.device.mac, None)
                    s.device.online = False
                    print(f"[device] {s.device.name} offline")
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass

    async def poll_loop(self) -> None:
        n = 0
        while True:
            n += 1
            sessions = [s for s in list(self.sessions.values()) if s.device]
            if sessions:
                await asyncio.gather(*[self._poll_one(s, n) for s in sessions])
            now = time.time()
            if now - self._last_sample >= self.sample_every:
                self._last_sample = now
                try:
                    self.history.record_snapshot(self.snapshot(), now)
                    self.history.prune(now - SAMPLE_RETENTION)
                except Exception as err:
                    print(f"[history] sample error: {err!r}")
            try:
                await self.evaluate_scenes(self.snapshot(), now)
            except Exception as err:
                print(f"[scene] evaluate error: {err!r}")
            await asyncio.sleep(POLL_SECONDS)

    async def _poll_one(self, s: Session, n: int) -> None:
        try:
            if not await s.refresh():
                print(f"[device] {s.device.name} poll timeout")
            elif n % DIAG_EVERY == 0:
                await s.diagnostics()
        except Exception as err:
            print(f"[device] poll error: {err!r}")

    def _threshold_ready(self, sc: dict, devices: dict, now: float) -> bool:
        """True once the condition held continuously for for_s seconds."""
        trig = sc["trigger"]
        d = devices.get(trig["mac"])
        st = self._scene_state.setdefault(sc["id"], {"since": None, "attempt": 0.0})
        if not d or not d.get("online"):
            st["since"] = None
            return False
        outlet = next((o for o in d.get("outlets", []) if o.get("n") == trig["outlet"]), None)
        if outlet is None or outlet.get("power_w") is None:
            st["since"] = None
            return False
        cond = outlet["power_w"] > trig["watts"] if trig["direction"] == "above" \
            else outlet["power_w"] < trig["watts"]
        if not cond:
            st["since"] = None
            return False
        if st["since"] is None:
            st["since"] = now
        return (now - st["since"]) >= trig["for_s"]

    @staticmethod
    def _schedule_due(trig: dict, now: float) -> bool:
        local = datetime.fromtimestamp(now)
        return local.weekday() in trig["days"] and local.strftime("%H:%M") == trig["time"]

    async def _fire_scene(self, sc: dict) -> bool:
        ok = True
        for a in sc["actions"]:
            s = self.session(a["mac"])
            if not s:
                print(f"[scene] {sc['name']!r}: device {a['mac']} offline, skipped")
                ok = False
                continue
            channels = [1, 2, 3, 4] if a["outlet"] == 0 else [a["outlet"]]
            try:
                if not await s.set_outlets(channels, a["on"]):
                    ok = False
            except Exception as err:
                print(f"[scene] {sc['name']!r}: command failed: {err!r}")
                ok = False
        return ok

    async def evaluate_scenes(self, snap: dict, now: float) -> None:
        devices = {d["mac"]: d for d in snap.get("devices", [])}
        for sc in self.scenes.all():
            if not sc.get("enabled"):
                continue
            ttype = sc["trigger"]["type"]
            if ttype == "manual":
                continue
            if ttype == "threshold":
                ready = self._threshold_ready(sc, devices, now)
            elif ttype == "schedule":
                ready = self._schedule_due(sc["trigger"], now)
            else:
                continue
            if not ready:
                continue
            st = self._scene_state.setdefault(sc["id"], {"since": None, "attempt": 0.0})
            if now - (st.get("attempt") or 0.0) < SCENE_COOLDOWN:
                continue
            st["attempt"] = now
            if await self._fire_scene(sc):
                self.scenes.mark_fired(sc["id"], now)
                st["since"] = None
                print(f"[scene] fired {sc['name']!r}")


PAGE = """<!doctype html>
<meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>power-strip</title>
<style>
:root{--bg:#0f1115;--card:#191d24;--on:#2ecc71;--off:#39424f;--fg:#e8ecf1;--dim:#8b96a5}
*{box-sizing:border-box}
body{margin:auto;padding:16px;max-width:720px;background:var(--bg);color:var(--fg);font:16px/1.4 system-ui,sans-serif}
h1{font-size:20px;margin:0}
.hint{color:var(--dim);font-size:13px;margin:6px 0 16px}
.card{background:var(--card);border-radius:14px;padding:14px;margin-bottom:16px}
.card.off{opacity:.45}
.head{display:flex;justify-content:space-between;align-items:center;gap:10px}
.mac{font-weight:600}
.dot{width:9px;height:9px;border-radius:50%;display:inline-block;background:#c0392b}
.dot.on{background:var(--on)}
.stats{color:var(--dim);font-size:13px;margin:8px 0 12px}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}
button{border:0;border-radius:12px;padding:14px 10px;font:inherit;color:var(--fg);background:var(--off);text-align:left;width:100%;cursor:pointer}
button.on{background:var(--on);color:#06130a}
button small{display:block;opacity:.85;font-size:12px}
button:disabled{opacity:.5;cursor:wait}
input{width:100%;border:1px solid var(--off);border-radius:10px;padding:12px 10px;font:inherit;color:var(--fg);background:#0b0e12;margin-top:4px}
label{display:block;font-size:13px;color:var(--dim);margin-bottom:10px}
#provStatus{white-space:pre-wrap}
</style>
<h1>power-strip</h1>
<div class=hint id=hint></div>
<div id=app></div>
<div class=card>
  <div class=head><span class=mac>Link a new strip · ربط مشترك جديد</span></div>
  <div class=stats>1. Hold the strip button ~10s until fast blink · اضغط زر المشترك 10 ثوانٍ حتى الوميض السريع<br>2. Join the TONLY_TAP_* Wi-Fi from THIS machine · اتصل بشبكة المشترك من هذا الجهاز<br>3. Fill the form and press Provision · املأ البيانات واضغط ربط<br>4. Back to home Wi-Fi · أعِد الجهاز لشبكة المنزل</div>
  <label>Server IP — the strip will dial it<input id=provIp placeholder="192.168.1.14"></label>
  <label>Home Wi-Fi name (SSID)<input id=provSsid placeholder="YOURWIFI"></label>
  <label>Home Wi-Fi password<input id=provPw type=password placeholder="••••••••"></label>
  <label>Setup host (advanced, default 192.168.1.1)<input id=provHost placeholder="192.168.1.1"></label>
  <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px">
    <button onclick="probe()">Test · اختبار</button>
    <button id=provBtn class=on onclick="provision()">Provision · ربط</button>
  </div>
  <div class=stats id=provStatus></div>
</div>
<script>
const api=async(p,b)=>(await fetch(p,b?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)}:undefined)).json();
const cmd=(mac,outlet,on)=>{api('api/onoff',{mac,outlet,on}).catch(()=>{}).then(refresh)};
const val=id=>document.getElementById(id).value.trim();
async function probe(){
  const st=document.getElementById('provStatus');
  st.textContent='Testing… · جارٍ الاختبار…';
  try{
    const r=await api('api/provision_probe?host='+encodeURIComponent(val('provHost')||'192.168.1.1'));
    st.textContent=r.reachable
      ?'Setup reachable ✓ — ready to link · الخدمة متاحة ✓ — جاهز للربط'
      :'Not reachable: join TONLY_TAP_* from THIS machine first · غير متاحة: اتصل بشبكة TONLY_TAP_* من هذا الجهاز أولاً';
  }catch(e){st.textContent='Request failed: '+e}
}
async function provision(){
  const b=document.getElementById('provBtn'),st=document.getElementById('provStatus');
  b.disabled=true;st.textContent='Linking… · جارٍ الربط…';
  try{
    const r=await api('api/provision',{server_ip:val('provIp'),ssid:val('provSsid'),password:document.getElementById('provPw').value,host:val('provHost')||undefined});
    st.textContent=(r.ok
      ?'Done ✓ — now watch: TONLY_TAP_* should DISAPPEAR within a minute (strip rebooting onto home Wi-Fi). If it stays visible, the SSID/password was wrong or not 2.4 GHz — try again.\nتم ✓ — راقب الآن: شبكة TONLY_TAP_* يجب أن تختفي خلال دقيقة (المشترك يعيد التشغيل على واي فاي البيت). لو فضلت ظاهرة، الاسم/كلمة غلط أو مش 2.4 جيجا — حاول تاني.'
      :'Failed: '+(r.error||'unknown'))+'\n'+(r.steps||[]).join('\n');
  }catch(e){st.textContent='Request failed: '+e}
  b.disabled=false;refresh();
}
function render(s){
  document.getElementById('hint').textContent =
    `server ${s.local_ip} | strip connects to TCP ${s.port} | provision with: power-strip.py provision --ip ${s.local_ip}`;
  const ip=document.getElementById('provIp');
  if(ip&&!ip.value)ip.value=s.local_ip;
  document.getElementById('app').innerHTML = s.devices.map(d=>`
  <div class="card${d.online?'':' off'}">
    <div class=head>
      <span class=mac>${d.name} <span class="dot${d.online?' on':''}"></span></span>
      <button class="on-off${d.on?' on':''}" onclick="cmd('${d.mac}',0,${!d.on})" style="width:auto;padding:10px 16px">${d.on?'ALL OFF':'ALL ON'}</button>
    </div>
    <div class=stats>${d.model} fw ${d.fw} | ${d.power_w} W | ${d.energy_kwh} kWh${d.voltage?` | ${d.voltage} V | ${d.current_a} A`:''}${d.rssi?` | wifi ${d.rssi} dBm`:''}</div>
    <div class=grid>${d.outlets.map(o=>`
      <button class="${o.on?'on':''}" onclick="cmd('${d.mac}',${o.n},${!o.on})">${o.name||('Outlet '+o.n)}
        <small>${o.on?'ON':'OFF'} | ${o.power_w} W | ${o.temp_c} C</small></button>`).join('')}
    </div>
  </div>`).join('') || '<div class=card>No strip connected yet. Use the Link form below, then power-cycle the strip.</div>';
}
async function refresh(){try{render(await api('api/state'))}catch(e){}}
refresh();setInterval(refresh,2000);
</script>
"""


class Web(BaseHTTPRequestHandler):
    hub: Hub
    loop: asyncio.AbstractEventLoop
    local_ip: str
    port: int
    token: str = ""
    store: NameStore
    history: HistoryStore
    scenes: SceneStore
    _fails: dict[str, list] = {}
    _fails_lock = threading.Lock()
    RATE_MAX = 10
    RATE_WINDOW = 60.0

    def log_message(self, fmt, *args):
        pass

    def _client_ip(self) -> str:
        return self.client_address[0] if self.client_address else "?"

    def _rate_limited(self) -> bool:
        if not self.token:
            return False
        now = time.monotonic()
        with self._fails_lock:
            hits = [t for t in self._fails.get(self._client_ip(), []) if now - t < self.RATE_WINDOW]
            self._fails[self._client_ip()] = hits
            return len(hits) >= self.RATE_MAX

    def _record_fail(self) -> None:
        with self._fails_lock:
            self._fails.setdefault(self._client_ip(), []).append(time.monotonic())

    def _authorized(self) -> bool:
        """No token set -> open. Otherwise: X-Token header, HTTP Basic (browser) or ?t= (simple clients)."""
        if not self.token:
            return True
        if secrets.compare_digest(self.headers.get("X-Token", ""), self.token):
            return True
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Basic "):
            try:
                user, _, password = base64.b64decode(auth[6:]).decode("utf-8").partition(":")
            except Exception:
                return False
            if secrets.compare_digest(password, self.token) or secrets.compare_digest(user, self.token):
                return True
        return secrets.compare_digest(parse_qs(urlparse(self.path).query).get("t", [""])[0], self.token)

    def _deny(self):
        self._record_fail()
        body = b"power-strip: token required\n"
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="power-strip"')
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _deny_limited(self):
        body = b"power-strip: too many failed attempts, try later\n"
        self.send_response(429)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _check_auth(self) -> bool:
        if self._rate_limited():
            self._deny_limited()
            return False
        if not self._authorized():
            self._deny()
            return False
        return True

    def _json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if path not in ("/power-strip.py", "/power-strip.apk") and not self._check_auth():
            return
        if path.startswith("/api/state"):
            snap = self.hub.snapshot()
            snap["local_ip"], snap["port"] = self.local_ip, self.port
            self._json(snap)
        elif path.startswith("/api/names"):
            self._json({"names": self.store.all()})
        elif path.startswith("/api/scenes"):
            self._json({"scenes": self.scenes.all()})
        elif path.startswith("/api/history"):
            qs = parse_qs(urlparse(self.path).query)
            try:
                mac = str(qs.get("mac", [""])[0])
                outlet = int(qs.get("outlet", ["0"])[0])
                to_ts = float(qs.get("to", [str(time.time())])[0])
                from_ts = float(qs.get("from", [str(to_ts - 86400)])[0])
                bucket = str(qs.get("bucket", ["raw"])[0])
                if not MAC_RE.fullmatch(mac.upper()) or outlet not in (0, 1, 2, 3, 4) \
                        or bucket not in ("raw", "hour", "day") or to_ts < from_ts:
                    raise ValueError("bad params")
            except (ValueError, TypeError):
                self._json({"error": "bad request (mac, outlet 0-4, from, to, bucket raw|hour|day)"}, 400)
                return
            self._json({"mac": mac.upper(), "outlet": outlet, "bucket": bucket,
                        "points": self.history.query(mac, outlet, from_ts, to_ts, bucket)})
        elif path.startswith("/api/report"):
            qs = parse_qs(urlparse(self.path).query)
            mac = str(qs.get("mac", [""])[0])
            period = str(qs.get("period", ["today"])[0])
            if not MAC_RE.fullmatch(mac.upper()) or period not in ("today", "week", "month"):
                self._json({"error": "bad request (mac, period today|week|month)"}, 400)
                return
            from_ts, to_ts, bucket = report_window(period)
            rows = self.history.rows_for_report(mac, from_ts, to_ts, bucket)
            report = compute_report(rows, from_ts, to_ts)
            report.update({"mac": mac.upper(), "period": period, "bucket": bucket})
            self._json(report)
        elif path.startswith("/api/provision_info"):
            cands = local_ipv4s()
            self._json({"local_ips": cands, "default_ip": self.local_ip,
                        "setup_host": SETUP_HOST, "setup_port": SETUP_PORT})
        elif path.startswith("/api/provision_probe"):
            qs = parse_qs(urlparse(self.path).query)
            probe_host = qs.get("host", [SETUP_HOST])[0] or SETUP_HOST
            try:
                with socket.create_connection((probe_host, SETUP_PORT), timeout=3):
                    self._json({"reachable": True, "host": probe_host, "port": SETUP_PORT})
            except OSError as err:
                self._json({"reachable": False, "host": probe_host, "port": SETUP_PORT,
                            "error": f"{err}"})
        elif path in ("/", "/index.html"):
            body = PAGE.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif path == "/power-strip.py":               # public: lets a phone/borrowed laptop fetch this file to run `provision`
            body = Path(__file__).resolve().read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/x-python")
            self.send_header("Content-Disposition", 'attachment; filename="power-strip.py"')
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif path == "/power-strip.apk":              # public: the Android app, once built (android/ -> gradlew assembleDebug)
            apk = Path(__file__).resolve().parent / "android/app/build/outputs/apk/debug/app-debug.apk"
            if not apk.is_file():
                self._json({"error": "no APK built yet: cd android && gradlew assembleDebug"}, 404)
                return
            body = apk.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", 'attachment; filename="power-strip.apk"')
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self._json({"error": "not found"}, 404)

    def do_POST(self):
        path = urlparse(self.path).path
        if not self._check_auth():
            return
        if path.startswith("/api/provision"):
            self._handle_provision()
            return
        if path.startswith("/api/names"):
            self._handle_names()
            return
        if path == "/api/scenes":
            self._handle_scene_create()
            return
        if path.startswith("/api/scenes/") and path.endswith("/run"):
            self._handle_scene_run(path[len("/api/scenes/"):-len("/run")])
            return
        if not path.startswith("/api/onoff"):
            self._json({"error": "not found"}, 404)
            return
        try:
            req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
            mac = str(req["mac"]).upper()
            outlet = int(req["outlet"])
            on = bool(req["on"])
        except Exception:
            self._json({"error": "bad request"}, 400)
            return

        s = self.hub.session(mac)
        if not s:
            self._json({"error": "device offline"}, 503)
            return
        channels = [1, 2, 3, 4] if outlet == 0 else [outlet]
        if not set(channels) <= {1, 2, 3, 4}:
            self._json({"error": "bad outlet"}, 400)
            return
        try:
            fut = asyncio.run_coroutine_threadsafe(s.set_outlets(channels, on), self.loop)
            ok = fut.result(timeout=10)
        except Exception as err:
            self._json({"error": f"command failed: {err}"}, 502)
            return
        self._json({"ok": bool(ok), "confirmed": ok})

    def _handle_provision(self):
        """Run the strip setup flow from the web UI. Blocking sockets are fine here:
        each request already runs in its own thread (ThreadingHTTPServer)."""
        try:
            req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
            server_ip = str(req.get("server_ip") or self.local_ip)
            ssid = str(req.get("ssid") or "")
            password = str(req.get("password") or "")
            setup_host = str(req.get("host") or SETUP_HOST)
            wait = min(max(float(req.get("wait", 20.0)), 1.0), 120.0)
        except Exception:
            self._json({"error": "bad request"}, 400)
            return
        if not ssid:
            self._json({"error": "ssid required"}, 400)
            return
        client = self._client_ip()
        print(f"[provision] from {client} setup={setup_host}:{SETUP_PORT} server_ip={server_ip} ssid={ssid!r} ...")
        res = provision_strip(setup_host, server_ip, ssid, password, wait=wait)
        print(f"[provision] from {client} -> {'OK' if res['ok'] else 'FAIL'} steps={res['steps']} error={res['error']}")
        self._json(res, 200 if res["ok"] else (400 if res.get("client_error") else 502))

    def _handle_names(self):
        try:
            req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
            mac = str(req.get("mac") or "")
            strip = req.get("strip", None)
            outlets = req.get("outlets", None)
            if outlets is not None and not isinstance(outlets, dict):
                raise ValueError("outlets must be an object")
            saved = self.store.set(mac, strip=strip, outlets=outlets)
        except ValueError as err:
            self._json({"error": str(err)}, 400)
            return
        except Exception:
            self._json({"error": "bad request"}, 400)
            return
        self._json({"ok": True, "names": saved})

    def _scene_body(self) -> dict:
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        except Exception:
            raise ValueError("bad request")
        if not isinstance(body, dict):
            raise ValueError("bad request")
        return body

    def _handle_scene_create(self):
        try:
            scene = self.scenes.create(self._scene_body())
        except ValueError as err:
            self._json({"error": str(err)}, 400)
            return
        self._json({"ok": True, "scene": scene}, 201)

    def _handle_scene_run(self, sid: str):
        sc = self.scenes.get(sid)
        if not sc:
            self._json({"error": "not found"}, 404)
            return
        try:
            fut = asyncio.run_coroutine_threadsafe(self.hub._fire_scene(sc), self.loop)
            ok = fut.result(timeout=30)
        except Exception as err:
            self._json({"error": f"command failed: {err}"}, 502)
            return
        if ok:
            self.scenes.mark_fired(sid, time.time())
            self._json({"ok": True, "fired": True})
        else:
            self._json({"error": "device offline"}, 503)

    def do_PUT(self):
        path = urlparse(self.path).path
        if not self._check_auth():
            return
        if path.startswith("/api/scenes/"):
            sid = path[len("/api/scenes/"):]
            try:
                scene = self.scenes.update(sid, self._scene_body())
            except ValueError as err:
                self._json({"error": str(err)}, 400)
                return
            if not scene:
                self._json({"error": "not found"}, 404)
                return
            self._json({"ok": True, "scene": scene})
            return
        self._json({"error": "not found"}, 404)

    def do_DELETE(self):
        path = urlparse(self.path).path
        if not self._check_auth():
            return
        if path.startswith("/api/scenes/"):
            if self.scenes.delete(path[len("/api/scenes/"):]):
                self._json({"ok": True})
            else:
                self._json({"error": "not found"}, 404)
            return
        self._json({"error": "not found"}, 404)


def local_ipv4s() -> list[str]:
    try:
        ips = socket.gethostbyname_ex(socket.gethostname())[2]
    except OSError:
        ips = []
    return [i for i in ips if not i.startswith(("127.", "169.254."))]


def setup_command(cmd: str, expect: str, host: str, timeout: float = 6.0, setup_port: int = SETUP_PORT) -> str:
    with socket.create_connection((host, setup_port), timeout=timeout) as s:
        s.settimeout(timeout)
        s.sendall((cmd + "\r\n").encode())
        buf = b""
        deadline = time.monotonic() + timeout
        while b"\n" not in buf and time.monotonic() < deadline:
            try:
                chunk = s.recv(1024)
            except socket.timeout:
                break
            if not chunk:
                break
            buf += chunk
    text = buf.decode("utf-8", "replace").strip()
    if expect and expect not in text:
        raise SystemExit(f"strip answered {text!r}, expected {expect!r} (command {cmd!r})")
    return text


def provision_strip(setup_host: str, server_ip: str, ssid: str, password: str,
                    wait: float = 20.0, setup_port: int = SETUP_PORT) -> dict:
    """Point one strip at a server. Shared by the CLI and the web UI.

    Returns {"ok", "steps", "error", "client_error"}; steps are the strip's raw answers.
    The caller must be on the strip's setup network (TONLY_TAP_*).
    """
    ssid = ssid.strip()     # stray keyboard spaces would join nothing; passwords stay byte-exact
    for value in (ssid, password):
        if any(c in value for c in ":\r\n"):
            return {"ok": False, "steps": [], "client_error": True,
                    "error": "the strip's command protocol cannot use ':' or newlines in the SSID/password"}
    try:
        server_ip = str(ipaddress.IPv4Address(server_ip))     # never pass an unchecked string into the strip's command protocol
    except ValueError:
        return {"ok": False, "steps": [], "client_error": True,
                "error": f"server IP must be an IPv4 address (got {server_ip!r})"}

    deadline = time.monotonic() + wait
    while True:
        try:
            with socket.create_connection((setup_host, setup_port), timeout=2):
                break
        except OSError:
            if time.monotonic() > deadline:
                return {"ok": False, "steps": [], "client_error": False,
                        "error": f"setup service not reachable at {setup_host}:{setup_port} — "
                                 "strip blinking fast? THIS machine on TONLY_TAP_*? / "
                                 "المشترك يومض بسرعة؟ هذا الجهاز متصل بشبكة TONLY_TAP_*؟"}
            time.sleep(2)

    steps: list[str] = []
    try:
        steps.append(setup_command(f"up:ip:{server_ip}", "up:ip:ip_ok", setup_host, setup_port=setup_port))
        steps.append(setup_command(f"up:connect:{ssid}:{password}", "up:connect:connect_ok", setup_host, setup_port=setup_port))
    except SystemExit as err:
        return {"ok": False, "steps": steps, "client_error": False, "error": str(err)}
    return {"ok": True, "steps": steps, "client_error": False, "error": None}


def cmd_provision(args) -> int:
    if not args.ip:
        print("Need the IP this PC will have on your home Wi-Fi (the strip connects to it).")
        cands = local_ipv4s()
        print("Candidates found right now:", ", ".join(cands) or "none")
        print("Run `python power-strip.py` on the home Wi-Fi first; it prints the exact IP. Then:")
        print('  python power-strip.py provision --ip 192.168.0.50 --ssid "YOURWIFI" --password "..."')
        return 2

    if any(c in value for value in (args.ssid, args.password) for c in ":\r\n"):
        raise SystemExit("the strip's command protocol cannot use ':' or newlines in the SSID/password")
    try:
        args.ip = str(ipaddress.IPv4Address(args.ip))
    except ValueError:
        raise SystemExit(f"--ip must be an IPv4 address (got {args.ip!r})")

    print(f"Looking for the strip's setup service at {args.host}:{SETUP_PORT} ...")
    res = provision_strip(args.host, args.ip, args.ssid, args.password, wait=args.wait)
    for step in res["steps"]:
        print(f"  {step}")
    if not res["ok"]:
        if not res["steps"]:
            print("Not reachable. Do this first:")
            print("  1. hold the strip's main button ~10s until the LED blinks fast")
            print("  2. join the Wi-Fi network 'TONLY_TAP_XXXXXXX' from this PC")
            print("     (password: LGU_XXXXXXX - the same 7 characters as in the name)")
            print("  3. re-run this command")
            return 1
        raise SystemExit(res["error"])
    print(f"\nDone. Reconnect this PC to your home Wi-Fi and start `python power-strip.py`.")
    print(f"The strip will connect to {args.ip}:{DEVICE_PORT} shortly.")
    return 0


async def cmd_serve(args) -> int:
    if args.port != DEVICE_PORT:
        print(f"  warning: strip firmware always dials TCP {DEVICE_PORT}; using --port {args.port} is for testing only")
    hub = Hub()
    server = await asyncio.start_server(hub.handle_client, "0.0.0.0", args.port)
    candidates = local_ipv4s()
    ip = args.ip or (candidates or ["127.0.0.1"])[0]

    Web.hub, Web.loop, Web.local_ip, Web.port = hub, asyncio.get_running_loop(), ip, args.port
    Web.store = hub.store
    Web.history = hub.history
    Web.scenes = hub.scenes
    Web.token = args.token
    httpd = ThreadingHTTPServer(("0.0.0.0", args.web_port), Web)
    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()

    print(f"power-strip")
    print(f"  web UI (phone/PC)  http://{ip}:{args.web_port}")
    print(f"  strip server       TCP {args.port}")
    print(f"  auth               {'token required' if args.token else 'NONE - only safe on a trusted LAN'}")
    if args.token:
        print(f"                     browser: user 'power-strip' + the token as password; app: Settings -> Token")
    print(f"  if not provisioned yet:  python power-strip.py provision --ip {ip} --ssid WIFI --password PW")
    if (Path(__file__).resolve().parent / "android/app/build/outputs/apk/debug/app-debug.apk").is_file():
        print(f"  android app        http://{ip}:{args.web_port}/power-strip.apk")
    if len(candidates) > 1 and not args.ip:
        print(f"  other local IPs: {', '.join(c for c in candidates if c != ip)}  (pick one with --ip if wrong)")
    print("  Ctrl+C to stop")
    async with server:
        await hub.poll_loop()
    return 0


def _fake_tags(state_on: set[int]) -> str:
    """A 'up:getinfo:' payload shaped like the real strip's, for offline checks."""
    return ":".join(
        f"{n}:{n * 111};{'on' if n in state_on else 'off'};{int(n in state_on)};0;0;"
        f"{5300 if n == 2 and n in state_on else 0};0000000A;00000000;00000000;0;00;{30 + n}"
        for n in (1, 2, 3, 4)
    )


async def _selftest() -> None:
    tmpdb = Path(tempfile.mkdtemp(prefix="power-strip-test-")) / "t.db"
    hub = Hub(store=NameStore(tmpdb))
    server = await asyncio.start_server(hub.handle_client, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    reader, writer = await asyncio.open_connection("127.0.0.1", port)

    # A stand-in for the strip: answers getinfo, records onoff commands, keeps relay state.
    state_on = {2}

    async def fake_strip():
        while True:
            raw = await reader.readline()
            if not raw:
                return
            line = raw.decode().strip()
            if line == "up:getinfo:all":
                writer.write(f"up:getinfo:{_fake_tags(state_on)}\r\n".encode())
                await writer.drain()
            elif line.startswith("up:onoff:"):
                _, _, ch, val = line.split(":")
                (state_on.add if val == "on" else state_on.discard)(int(ch))

    strip_task = asyncio.create_task(fake_strip())

    writer.write(b"up:bootinfo:LGU+-TAP-HW002;aabbccddeeff;001122334455;1.0.66;connect\r\n")
    writer.write(b"up:power_report:1:224500\r\n")
    writer.write(b"up:query:-52\r\n")
    await writer.drain()
    await asyncio.sleep(0.3)

    dev = hub.devices["AABBCCDDEEFF"]
    assert dev.outlets[1]["on"] is False and dev.outlets[2]["on"] is True
    assert dev.outlets[2]["power_w"] == 5.3 and dev.outlets[2]["energy_kwh"] == 0.01
    assert dev.outlets[1]["temp_c"] == 31 and dev.outlets[4]["temp_c"] == 34
    assert dev.voltage_v == 224.5 and dev.rssi == -52
    assert dev.current_a == 0.02

    assert await hub.session("aabbccddeeff").set_outlets([2], False) is True
    assert dev.outlets[2]["on"] is False and state_on == set()
    snapshot = hub.snapshot()["devices"][0]
    assert snapshot["on"] is False and snapshot["energy_kwh"] == 0.04

    writer.write(b"up:event:onoff:0:on\r\n")
    await writer.drain()
    await asyncio.sleep(0.3)
    assert all(dev.outlets[n]["on"] for n in (1, 2, 3, 4))

    # Fake strip setup-AP service: one TCP connection per setup command.
    async def fake_setup(reader: asyncio.StreamReader, writer2: asyncio.StreamWriter):
        raw = await reader.readline()
        line = raw.decode("utf-8", "replace").strip()
        if line.startswith("up:ip:"):
            writer2.write(b"up:ip:ip_ok\r\n")
        elif line.startswith("up:connect:"):
            writer2.write(b"up:connect:connect_ok\r\n")
        else:
            writer2.write(b"up:unknown\r\n")
        await writer2.drain()
        writer2.close()

    setup_server = await asyncio.start_server(fake_setup, "127.0.0.1", 0)
    setup_port = setup_server.sockets[0].getsockname()[1]
    loop = asyncio.get_running_loop()
    res = await loop.run_in_executor(
        None, lambda: provision_strip("127.0.0.1", "192.168.1.14", "HomeWiFi", "secret123",
                                      wait=5.0, setup_port=setup_port))
    assert res["ok"] is True and res["steps"] == ["up:ip:ip_ok", "up:connect:connect_ok"], res
    assert provision_strip("127.0.0.1", "not-an-ip", "s", "p", wait=0.1, setup_port=setup_port)["ok"] is False
    assert provision_strip("127.0.0.1", "192.168.1.14", "a:b", "p", wait=0.1, setup_port=setup_port)["ok"] is False
    assert provision_strip("127.0.0.1", "192.168.1.14", "s", "p", wait=0.2, setup_port=1)["ok"] is False
    setup_server.close()

    # Custom names round-trip through sqlite and appear in snapshots.
    store = hub.store
    saved = store.set("aabbccddeeff", strip="  Living Room\n", outlets={1: "TV", 5: "nope", "x": "nan"})
    assert saved == {"strip": "Living Room", "outlets": {1: "TV"}}, saved
    assert store.get("AABBCCDDEEFF") == saved
    assert store.get("000000000000") == {"strip": "", "outlets": {}}
    assert set(store.all()) == {"AABBCCDDEEFF"}
    try:
        store.set("not-a-mac")
        raise AssertionError("bad mac accepted")
    except ValueError:
        pass
    snap = hub.snapshot()["devices"][0]
    assert snap["name"] == "Living Room", snap["name"]
    assert snap["outlets"][0]["name"] == "TV"
    assert snap["outlets"][1]["name"] == "Outlet 2"
    saved = store.set("AABBCCDDEEFF", outlets={1: ""})
    assert hub.snapshot()["devices"][0]["outlets"][0]["name"] == "Outlet 1"

    # History: record, bucket, prune, report incl. counter reset.
    hist = hub.history
    base = 1_700_000_000
    for i in range(5):
        hist.record_snapshot({"devices": [{
            "mac": "AABBCCDDEEFF", "online": True, "voltage": 220.0,
            "outlets": [
                {"n": 1, "power_w": 10.0 * (i + 1), "energy_kwh": 0.01 * (i + 1), "temp_c": 30},
                {"n": 2, "power_w": 0.0, "energy_kwh": 0.005, "temp_c": 31},
            ]}]}, base + i * 60)
    assert len(hist.query("aabbccddeeff", 1, base, base + 600)) == 5
    assert len(hist.query("AABBCCDDEEFF", 0, base, base + 600)) == 10
    hours = hist.query("AABBCCDDEEFF", 1, base, base + 600, "hour")
    assert len(hours) == 1 and hours[0]["max_w"] == 50.0, hours
    rep = compute_report(hist.rows_for_report("AABBCCDDEEFF", base, base + 600, "raw"), base, base + 600)
    assert rep["outlets"][1]["kwh"] == 0.04 and rep["outlets"][1]["max_w"] == 50.0, rep
    assert rep["outlets"][1]["max_t"] == base + 240 and rep["total_kwh"] == 0.04, rep
    # Counter reboot mid-window: drop then fresh value counts only the fresh part.
    for ts, e in ((base + 360, 0.002), (base + 420, 0.007)):
        hist.record_snapshot({"devices": [{
            "mac": "AABBCCDDEEFF", "online": True, "voltage": 220.0,
            "outlets": [{"n": 1, "power_w": 5.0, "energy_kwh": e, "temp_c": 30}]}]}, ts)
    rep2 = compute_report(hist.rows_for_report("AABBCCDDEEFF", base, base + 500, "raw"), base, base + 500)
    assert rep2["outlets"][1]["kwh"] == 0.045, rep2
    assert hist.prune(base + 1000) >= 11
    assert hist.query("AABBCCDDEEFF", 0, base, base + 2000) == []
    f, t, b = report_window("today")
    assert b == "raw" and f <= time.time() <= t + 1
    assert report_window("week")[2] == "hour" and report_window("month")[2] == "hour"

    # Scenes: validation, threshold sustain, schedule match, offline fire.
    good_t = {"name": "Heater guard", "trigger": {"type": "threshold", "mac": "AABBCCDDEEFF",
              "outlet": 2, "direction": "above", "watts": 1000, "for_s": 20},
              "actions": [{"mac": "AABBCCDDEEFF", "outlet": 2, "on": False}]}
    sc = hub.scenes.create(good_t)
    assert sc["trigger"]["for_s"] == 20 and sc["enabled"] is True and sc["last_fired"] is None
    assert len(hub.scenes.all()) == 1 and hub.scenes.get(sc["id"])["name"] == "Heater guard"
    bad_specs = [
        {"trigger": {"type": "threshold", "mac": "AA", "outlet": 1, "direction": "above", "watts": 1},
         "actions": [{"mac": "AABBCCDDEEFF", "outlet": 1, "on": True}]},
        {"name": "x", "trigger": {"type": "threshold", "mac": "AABBCCDDEEFF", "outlet": 9,
         "direction": "above", "watts": 1}, "actions": [{"mac": "AABBCCDDEEFF", "outlet": 1, "on": True}]},
        {"name": "x", "trigger": {"type": "schedule", "time": "25:00"},
         "actions": [{"mac": "AABBCCDDEEFF", "outlet": 0, "on": False}]},
        {"name": "x", "trigger": {"type": "schedule", "time": "07:30", "days": [7]},
         "actions": [{"mac": "AABBCCDDEEFF", "outlet": 0, "on": False}]},
        {"name": "x", "trigger": {"type": "manual"}, "actions": []},
        {"name": "   ", "trigger": {"type": "manual"},
         "actions": [{"mac": "AABBCCDDEEFF", "outlet": 1, "on": True}]},
    ]
    for bad in bad_specs:
        try:
            hub.scenes.create(bad)
            raise AssertionError(f"accepted {bad}")
        except ValueError:
            pass

    def snap_with(power, online=True):
        d = {"mac": "AABBCCDDEEFF", "online": online,
             "outlets": [{"n": 2, "power_w": power}]}
        return {d["mac"]: d}

    t0 = 1_700_000_000.0
    assert hub._threshold_ready(sc, snap_with(1500.0), t0) is False
    assert hub._threshold_ready(sc, snap_with(1500.0), t0 + 10) is False
    assert hub._threshold_ready(sc, snap_with(1500.0), t0 + 21) is True
    assert hub._threshold_ready(sc, snap_with(10.0), t0 + 22) is False
    assert hub._threshold_ready(sc, snap_with(1500.0, online=False), t0 + 30) is False
    assert await hub._fire_scene(sc) is True  # fake strip is connected: real round-trip
    now_local = datetime.now()
    sched = {"id": "s", "trigger": {"type": "schedule", "time": now_local.strftime("%H:%M"),
             "days": [now_local.weekday()]}}
    assert Hub._schedule_due(sched["trigger"], time.time()) is True
    other_day = {"time": "00:00", "days": [(now_local.weekday() + 1) % 7]}
    assert Hub._schedule_due(other_day, time.time()) is False
    await hub.evaluate_scenes({"devices": []}, time.time())
    upd = hub.scenes.update(sc["id"], {**good_t, "enabled": False})
    assert upd and upd["enabled"] is False
    assert hub.scenes.delete(sc["id"]) is True and hub.scenes.delete(sc["id"]) is False
    assert hub.scenes.get(sc["id"]) is None

    strip_task.cancel()
    writer.close()
    server.close()
    print("selftest: parser + command round-trip + provision + names + history + scenes OK")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd")
    s = sub.add_parser("serve", help="run device server + web UI (default)")
    s.add_argument("--port", type=int, default=DEVICE_PORT, help=f"device TCP port (default {DEVICE_PORT}; the strip always dials {DEVICE_PORT}, change only for testing)")
    s.add_argument("--web-port", type=int, default=WEB_PORT)
    s.add_argument("--ip", help="IP to print in the UI/provision hint (default: auto-detect)")
    s.add_argument("--token", default="", help="require this token (password) for the web UI/API; use it whenever the port is reachable from the internet")
    p = sub.add_parser("provision", help="send home Wi-Fi + this PC's IP to a strip in setup mode")
    p.add_argument("--ip", help="IPv4 this PC will have on the home Wi-Fi (what the strip connects to)")
    p.add_argument("--ssid", required=True)
    p.add_argument("--password", required=True)
    p.add_argument("--host", default=SETUP_HOST, help=f"strip setup address (default {SETUP_HOST})")
    p.add_argument("--wait", type=float, default=20.0, help="seconds to wait for the strip setup service")
    sub.add_parser("selftest", help="offline protocol check")
    args = ap.parse_args()

    if args.cmd == "provision":
        return cmd_provision(args)
    if args.cmd == "selftest":
        asyncio.run(_selftest())
        return 0
    if args.cmd in (None, "serve"):
        args.port = getattr(args, "port", DEVICE_PORT)
        args.web_port = getattr(args, "web_port", WEB_PORT)
        args.ip = getattr(args, "ip", None)
        try:
            return asyncio.run(cmd_serve(args))
        except KeyboardInterrupt:
            return 0
    ap.error("unknown command")


if __name__ == "__main__":
    sys.exit(main())
