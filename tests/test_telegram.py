"""Tests for Telegram reminders: what gets sent when, the buttons and commands, and the SOCKS5 proxy.

Telegram is replaced by a small fake Bot API server, reached through a fake SOCKS5 proxy, so these run offline.

Run:  python3 -m unittest discover -s tests -v
"""
import datetime as dt
import json
import os
import socket
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from zoneinfo import ZoneInfo

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_work import start_server, stop_server  # noqa: E402

import app  # noqa: E402  (test_work puts the project on sys.path)

TOKEN = "123456789:" + "A" * 35
FAKE_HOST = "api.telegram.test"  # only the fake proxy knows this name


class FakeTelegram:
    """Just enough of the Bot API: getMe, getUpdates (long poll), sendMessage and the edit calls."""

    def __init__(self):
        self.calls, self.updates, self.next_update, self.next_msg, self.polls = [], [], 1, 100, 0
        self.cond = threading.Condition()
        fake = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
                _, bot, method = self.path.split("/", 2)
                if bot != f"bot{TOKEN}":
                    return self.reply({"ok": False, "error_code": 401, "description": "Unauthorized"})
                self.reply({"ok": True, "result": fake.handle(method, body)})

            def reply(self, obj):
                data = json.dumps(obj).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                try:
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):  # the app went away mid-poll
                    pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def handle(self, method, body):
        if method == "getUpdates":
            with self.cond:
                self.polls += 1
            deadline = time.time() + min(body.get("timeout", 0), 2)
            with self.cond:
                while True:
                    out = [u for u in self.updates if u["update_id"] >= body.get("offset", 0)]
                    if out or time.time() >= deadline:
                        return out
                    self.cond.wait(0.05)
        with self.cond:
            self.calls.append((method, body))
            if method == "getMe":
                return {"id": 123456789, "is_bot": True, "username": "daybook_test_bot"}
            if method == "sendMessage":
                self.next_msg += 1
                return {"message_id": self.next_msg, "chat": {"id": body["chat_id"]}, "text": body["text"]}
            return True

    def push(self, kind, payload):
        with self.cond:
            self.updates.append({"update_id": self.next_update, kind: payload})
            self.next_update += 1
            self.cond.notify_all()

    def sent(self, method="sendMessage"):
        with self.cond:
            return [b for m, b in self.calls if m == method]

    def wait_for(self, check, timeout=10):
        end = time.time() + timeout
        while time.time() < end:
            res = check()
            if res:
                return res
            time.sleep(0.05)
        raise AssertionError("timed out waiting")

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class FakeSocks5:
    """A SOCKS5 proxy with a username and password that resolves FAKE_HOST itself (like socks5h)."""

    def __init__(self, user="proxyuser", password="proxypass"):
        self.user, self.password, self.targets = user, password, []
        self.sock = socket.socket()
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(32)
        self.port = self.sock.getsockname()[1]
        threading.Thread(target=self.accept, daemon=True).start()

    def accept(self):
        while True:
            try:
                c, _ = self.sock.accept()
            except OSError:
                return
            threading.Thread(target=self.serve, args=(c,), daemon=True).start()

    @staticmethod
    def recv(c, n):
        buf = b""
        while len(buf) < n:
            chunk = c.recv(n - len(buf))
            if not chunk:
                raise OSError("closed")
            buf += chunk
        return buf

    def serve(self, c):
        try:
            _, n = self.recv(c, 2)
            if 2 not in self.recv(c, n):
                c.sendall(b"\x05\xff")
                return
            c.sendall(b"\x05\x02")
            self.recv(c, 1)
            user = self.recv(c, self.recv(c, 1)[0]).decode()
            pw = self.recv(c, self.recv(c, 1)[0]).decode()
            ok = (user, pw) == (self.user, self.password)
            c.sendall(b"\x01" + (b"\x00" if ok else b"\x01"))
            if not ok:
                return
            _, cmd, _, atyp = self.recv(c, 4)
            assert cmd == 1 and atyp == 3, "expected CONNECT by name"
            host = self.recv(c, self.recv(c, 1)[0]).decode()
            port = int.from_bytes(self.recv(c, 2), "big")
            self.targets.append(host)
            if host != FAKE_HOST:
                c.sendall(b"\x05\x04\x00\x01" + bytes(6))
                return
            with socket.create_connection(("127.0.0.1", port)) as up:
                c.sendall(b"\x05\x00\x00\x01" + bytes(6))
                back = threading.Thread(target=self.pipe, args=(up, c), daemon=True)
                back.start()
                self.pipe(c, up)
                back.join(5)
        except OSError:
            pass
        finally:
            c.close()

    @staticmethod
    def pipe(a, b):
        try:
            while True:
                data = a.recv(65536)
                if not data:
                    break
                b.sendall(data)
        except OSError:
            pass
        finally:
            for s in (a, b):
                try:
                    s.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    def close(self):
        self.sock.close()


class TelegramServerTest(unittest.TestCase):
    """The whole loop against the running server: set up, link a chat, alarms, buttons and commands."""

    @classmethod
    def setUpClass(cls):
        cls.tg = FakeTelegram()
        cls.proxy = FakeSocks5()
        start_server(cls, {"TELEGRAM_API_URL": f"http://{FAKE_HOST}:{cls.tg.port}", "TELEGRAM_POLL_SECONDS": "1",
                           "PUBLIC_URL": "https://daybook.test"})

    @classmethod
    def tearDownClass(cls):
        stop_server(cls)
        cls.tg.close()
        cls.proxy.close()

    def ok(self, method, path, body=None):
        status, res = self.c.call(method, path, body)
        self.assertEqual(status, 200, res)
        return res

    def err(self, method, path, body=None):
        status, res = self.c.call(method, path, body)
        self.assertEqual(status, 400, res)
        return res["error"]

    def proxy_body(self, password="proxypass"):
        return {"host": "127.0.0.1", "port": self.proxy.port, "username": "proxyuser", "password": password}

    def tasks(self):
        return {t["title"]: t for t in self.ok("GET", "/api/tasks")["tasks"]}

    def test_full_flow(self):
        tg = self.tg
        self.assertFalse(self.ok("GET", "/api/telegram")["configured"])
        # Bad token, no proxy (the fake name doesn't resolve here), wrong proxy password.
        self.assertIn("doesn't look like a bot token", self.err("PUT", "/api/telegram/bot", {"token": "nope"}))
        self.assertIn("Can't reach Telegram", self.err("PUT", "/api/telegram/bot", {"token": TOKEN}))
        self.assertIn("username or password", self.err("PUT", "/api/telegram/bot", {
            "token": TOKEN, "proxy": self.proxy_body("wrong")}))
        self.assertIn("SOCKS5", self.err("PUT", "/api/telegram/bot", {
            "token": TOKEN, "proxy": {"host": "http://127.0.0.1:8080"}}))
        self.assertIn("Unknown time zone", self.err("PUT", "/api/telegram/bot", {
            "token": TOKEN, "proxy": self.proxy_body(), "tz": "Mars/Base"}))

        st = self.ok("PUT", "/api/telegram/bot", {"token": TOKEN, "proxy": self.proxy_body(), "tz": "Asia/Tehran"})
        self.assertTrue(st["configured"])
        self.assertFalse(st["linked"])
        self.assertEqual(st["bot"], "daybook_test_bot")
        self.assertEqual(st["proxy"], {"host": "127.0.0.1", "port": self.proxy.port, "username": "proxyuser",
                                       "has_password": True})
        self.assertNotIn(TOKEN, json.dumps(st))  # the token never comes back
        self.assertTrue(st["link_url"].startswith("https://t.me/daybook_test_bot?start="))
        self.assertIn(FAKE_HOST, self.proxy.targets)  # the proxy looked the name up

        # Changing only the proxy keeps the saved password and the token.
        st = self.ok("PUT", "/api/telegram/bot", {"proxy": {"host": f"socks5://proxyuser@127.0.0.1:{self.proxy.port}"}})
        self.assertTrue(st["proxy"]["has_password"])
        self.assertEqual(st["link_code"], st["link_url"].rsplit("=", 1)[1])

        # A stranger can't link; the person with the code can.
        tg.push("message", {"message_id": 1, "chat": {"id": 777, "first_name": "Eve"}, "text": "/start guess"})
        tg.push("message", {"message_id": 2, "chat": {"id": 555, "first_name": "Sam"}, "text": f"/start {st['link_code']}"})
        st = tg.wait_for(lambda: (lambda s: s if s["linked"] else None)(self.ok("GET", "/api/telegram")))
        self.assertEqual(st["chat"], "Sam")
        self.assertIsNone(st["link_url"])
        welcome = tg.wait_for(lambda: [m for m in tg.sent() if m["chat_id"] == 555 and "Connected" in m["text"]])
        self.assertIn("08:00", welcome[0]["text"])
        tg.wait_for(lambda: tg.sent("setMyCommands"))
        self.assertTrue([m for m in tg.sent() if m["chat_id"] == 777 and "Settings" in m["text"]])
        self.ok("POST", "/api/telegram/test")

        # An alarm on a to-do rings, with buttons; snoozing it moves the reminder.
        now = int(time.time())
        t = self.ok("POST", "/api/tasks", {"title": "Call the bank", "remind_at": now - 5, "priority": 3})
        self.assertEqual(t["remind_at"], now - 5)
        ring = tg.wait_for(lambda: [m for m in tg.sent() if "Call the bank" in m["text"]])[0]
        self.assertIn("High priority", ring["text"])
        datas = [b["callback_data"] for row in ring["reply_markup"]["inline_keyboard"] for b in row]
        self.assertIn(f"snz:{t['id']}:10", datas)
        tg.push("callback_query", {"id": "cb1", "data": f"snz:{t['id']}:10", "message": {
            "message_id": 101, "chat": {"id": 555}, "text": "⏰ Call the bank",
            "entities": [{"type": "bold", "offset": 2, "length": 13}, {"type": "bot_command", "offset": 0, "length": 1}]}})
        edit = tg.wait_for(lambda: tg.sent("editMessageText"))[0]
        self.assertIn("Snoozed until", edit["text"])
        self.assertEqual([e["type"] for e in edit["entities"]], ["bold", "italic"])
        self.assertEqual(edit["reply_markup"], {"inline_keyboard": []})
        self.assertTrue(tg.sent("answerCallbackQuery"))
        self.assertGreater(self.tasks()["Call the bank"]["remind_at"], now + 500)

        # Plain text adds a to-do (first line title, the rest notes), and Undo removes it.
        tg.push("message", {"message_id": 3, "chat": {"id": 555}, "text": "Buy milk\nsemi-skimmed"})
        added = tg.wait_for(lambda: [m for m in tg.sent() if "Added to your to-dos" in m["text"]])[0]
        self.assertEqual(self.tasks()["Buy milk"]["notes"], "semi-skimmed")
        undo = added["reply_markup"]["inline_keyboard"][0][0]["callback_data"]
        tg.push("callback_query", {"id": "cb2", "data": undo, "message": {"message_id": 102, "chat": {"id": 555},
                                                                          "text": "Added to your to-dos: Buy milk"}})
        tg.wait_for(lambda: "Buy milk" not in self.tasks())
        # Strangers can't add to-dos.
        tg.push("message", {"message_id": 4, "chat": {"id": 777}, "text": "Spam"})
        # /remind makes a to-do with an alarm.
        tg.push("message", {"message_id": 5, "chat": {"id": 555}, "text": "/remind 20m Tea"})
        tg.wait_for(lambda: "Tea" in self.tasks())
        self.assertAlmostEqual(self.tasks()["Tea"]["remind_at"], int(time.time()) + 1200, delta=15)
        self.assertNotIn("Spam", self.tasks())

        # Settings: validation, then saved values come back.
        self.assertEqual(self.c.call("PUT", "/api/telegram", {"lead": 7})[0], 400)
        self.assertEqual(self.c.call("PUT", "/api/telegram", {"brief_time": "25:00"})[0], 400)
        s = self.ok("PUT", "/api/telegram", {"brief": False, "brief_time": "7:30", "lead": 10, "timer_hours": 0,
                                             "ring_again": False})["settings"]
        self.assertEqual(s, {"brief": False, "brief_time": "07:30", "lead": 10, "timer_hours": 0, "ring_again": False})

        # Disconnect: the worker stops calling Telegram.
        self.ok("DELETE", "/api/telegram")
        self.assertFalse(self.ok("GET", "/api/telegram")["configured"])
        time.sleep(3)
        n = tg.polls
        time.sleep(2)
        self.assertEqual(tg.polls, n)


class FakeBot:
    """Stands in for TgBot in the in-process tests."""

    def __init__(self):
        self.sent, self.dropped, self.closed, self.calls, self.next = [], [], [], [], 1

    def send(self, text, buttons=None, chat=None):
        self.sent.append((text, buttons))
        self.next += 1
        return self.next

    def drop_buttons(self, msg_id):
        self.dropped.append(msg_id)

    def close(self, msg, status):
        self.closed.append(status)

    def call(self, method, **params):
        self.calls.append(method)

    def texts(self):
        return [t for t, _ in self.sent]


class TelegramRulesTest(unittest.TestCase):
    """When things are sent, in-process with a fake clock. Local time is Tehran (UTC+3:30, no DST)."""

    zone = ZoneInfo("Asia/Tehran")

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old_dir = app.DATA_DIR
        app.DATA_DIR = self.tmp.name
        self.db = app.connect()
        self.db.executescript(app.SCHEMA)
        app.migrate(self.db)
        app.create_user(self.db, "sam", "secret123")
        self.uid = self.db.execute("SELECT id FROM users").fetchone()[0]
        self.db.execute("INSERT INTO telegram (user_id, bot_token, chat_id, tz, created_at) VALUES (?,?,?,?,?)",
                        (self.uid, TOKEN, 555, "Asia/Tehran", 0))
        self.db.commit()
        self.bot = FakeBot()

    def tearDown(self):
        self.db.close()
        app.DATA_DIR = self.old_dir
        self.tmp.cleanup()

    def at(self, y, mo, d, hh, mm=0):
        return int(dt.datetime(y, mo, d, hh, mm, tzinfo=self.zone).timestamp())

    def ctx(self, now, **settings):
        if settings:
            s = app.tg_settings(app.tg_row(self.db, self.uid))
            s.update(settings)
            self.db.execute("UPDATE telegram SET settings=?", (json.dumps(s),))
        return app.TgCtx(self.db, app.tg_row(self.db, self.uid), now)

    def task(self, title, **kw):
        cols = {"user_id": self.uid, "title": title, "created_at": 0, **kw}
        cur = self.db.execute(f"INSERT INTO tasks ({','.join(cols)}) VALUES ({','.join('?' * len(cols))})",
                              tuple(cols.values()))
        return cur.lastrowid

    def block(self, title, start, end, **kw):
        cols = {"user_id": self.uid, "title": title, "start_ts": start, "end_ts": end, "updated_at": 0, **kw}
        cur = self.db.execute(f"INSERT INTO plan_blocks ({','.join(cols)}) VALUES ({','.join('?' * len(cols))})",
                              tuple(cols.values()))
        return cur.lastrowid

    def press(self, data, now):
        app.tg_on_callback(self.db, self.bot, self.ctx(now), {"id": "q", "data": data, "message": {
            "message_id": 9, "chat": {"id": 555}, "text": "x"}})

    def test_alarm_rings_again_once_then_stops(self):
        t0 = self.at(2026, 11, 20, 14, 30)
        tid = self.task("Pay rent", remind_at=t0, due="2026-11-20")
        app.tg_alarms(self.db, self.bot, self.ctx(t0 - 30))
        self.assertEqual(self.bot.sent, [])  # not yet
        app.tg_alarms(self.db, self.bot, self.ctx(t0 + 10))
        self.assertEqual(len(self.bot.sent), 1)
        self.assertIn("due today", self.bot.texts()[0])
        app.tg_alarms(self.db, self.bot, self.ctx(t0 + 200))
        self.assertEqual(len(self.bot.sent), 1)
        app.tg_alarms(self.db, self.bot, self.ctx(t0 + 320))
        self.assertEqual(len(self.bot.sent), 2)
        self.assertIn("(again)", self.bot.texts()[1])
        self.assertEqual(self.bot.dropped, [2])  # the first message loses its buttons
        app.tg_alarms(self.db, self.bot, self.ctx(t0 + 3600))
        self.assertEqual(len(self.bot.sent), 2)
        # Snoozing re-arms it; Done ends it.
        self.press(f"snz:{tid}:60", t0 + 3600)
        app.tg_alarms(self.db, self.bot, self.ctx(t0 + 7210))
        self.assertEqual(len(self.bot.sent), 3)
        self.press(f"done:{tid}", t0 + 7300)
        self.assertEqual(self.db.execute("SELECT done FROM tasks WHERE id=?", (tid,)).fetchone()[0], 1)
        self.assertTrue(self.bot.closed[-1].startswith("✓ Done"))

    def test_alarm_without_ring_again_and_ok(self):
        t0 = self.at(2026, 11, 20, 9)
        tid = self.task("Water plants", remind_at=t0)
        old = self.task("Ancient", remind_at=t0 - 13 * 3600)  # missed by more than 12 h: dropped
        app.tg_alarms(self.db, self.bot, self.ctx(t0, ring_again=False))
        app.tg_alarms(self.db, self.bot, self.ctx(t0 + 900))
        self.assertEqual(self.bot.texts(), ["⏰ <b>Water plants</b>"])
        self.press(f"ok:{tid}", t0 + 950)
        app.tg_alarms(self.db, self.bot, self.ctx(t0 + 2000, ring_again=True))  # answered: no second ring
        self.assertEqual(len(self.bot.sent), 1)
        self.assertEqual(self.bot.closed, ["👍 Got it"])
        self.assertEqual(self.db.execute("SELECT rings FROM tasks WHERE id=?", (old,)).fetchone()[0], 0)

    def test_heads_up_once_per_start_time(self):
        start = self.at(2026, 11, 20, 10)
        pid = self.db.execute("INSERT INTO projects (user_id, name, created_at) VALUES (?,?,0)", (self.uid, "Site")).lastrowid
        bid = self.block("Write", start, start + 7200, project_id=pid)
        app.tg_heads_up(self.db, self.bot, self.ctx(start - 360))
        self.assertEqual(self.bot.sent, [])
        app.tg_heads_up(self.db, self.bot, self.ctx(start - 290))
        app.tg_heads_up(self.db, self.bot, self.ctx(start - 200))
        self.assertEqual(len(self.bot.sent), 1)
        text, buttons = self.bot.sent[0]
        self.assertTrue(text.startswith("🗓 In 5 min · 10:00–12:00\n<b>Write</b>\nSite"), text)
        self.assertEqual(buttons[0][0]["callback_data"], f"start:{bid}")
        # Moved to later: a new heads-up. Pressing Start starts the timer on the block.
        self.db.execute("UPDATE plan_blocks SET start_ts=?, end_ts=? WHERE id=?", (start + 3600, start + 7200, bid))
        app.tg_heads_up(self.db, self.bot, self.ctx(start + 3600, lead=0))
        self.assertIn("Starting now", self.bot.texts()[1])
        self.press(f"start:{bid}", start + 3600)
        r = app.running_entry(self.db, self.uid)
        self.assertEqual((r["project_id"], r["description"]), (pid, "Write"))
        # With the timer already on it: no Start button. Off: nothing.
        bid2 = self.block("Write", start + 7200, start + 9000, project_id=pid)
        app.tg_heads_up(self.db, self.bot, self.ctx(start + 7000, lead=5))
        self.assertIsNone(self.bot.sent[2][1])
        self.block("Later", start + 9000, start + 9600)
        app.tg_heads_up(self.db, self.bot, self.ctx(start + 9000, lead=-1))
        self.assertEqual(len(self.bot.sent), 3)
        self.assertIsNotNone(bid2)

    def test_morning_brief(self):
        day = dt.date(2026, 11, 20)
        brief_at = self.at(2026, 11, 20, 8)
        app.tg_brief(self.db, self.bot, self.ctx(brief_at - 60))
        app.tg_brief(self.db, self.bot, self.ctx(brief_at + 30))
        self.assertEqual(self.bot.sent, [])  # nothing on: no message
        self.assertTrue(app.tg_was_sent(self.db, self.uid, "brief:2026-11-20"))
        # The next day, with things on.
        nxt = brief_at + 86400
        self.block("Gym", nxt + 9 * 3600, nxt + 10 * 3600)
        self.block("Ended", nxt - 3600, nxt - 1800)
        self.task("Late thing", due="2026-11-19")
        self.task("Due thing", due="2026-11-21", priority=3)
        self.task("Someday thing")
        self.task("Alarm thing", remind_at=nxt + 6 * 3600)
        self.db.execute("INSERT INTO time_entries (user_id, start_ts, end_ts) VALUES (?,?,?)",
                        (self.uid, brief_at + 3600, brief_at + 3600 + 5400))
        self.db.execute("INSERT INTO entries (user_id, date, meal, name, kcal, created_at) VALUES (?,?,?,?,?,?)",
                        (self.uid, day.isoformat(), "lunch", "Rice", 640, "x"))
        app.tg_brief(self.db, self.bot, self.ctx(nxt + 120))
        self.assertEqual(len(self.bot.sent), 1)
        text, buttons = self.bot.sent[0]
        self.assertEqual(text.split("\n"), [
            "☀️ <b>Saturday 21 November</b>", "", "<b>Planned</b>", "17:00–18:00  Gym", "",
            "<b>To-do</b>", "⚠️ Late thing · 2 days late", "• Due thing ❗", "• Alarm thing · ⏰ 14:00", "",
            "Yesterday: 1 h 30 min tracked · 640 of 2,000 kcal"])
        app.tg_brief(self.db, self.bot, self.ctx(nxt + 600))
        self.assertEqual(len(self.bot.sent), 1)  # once a day
        # Shamsi dates when the app uses them; nothing when it's off or too late.
        self.db.execute("UPDATE profiles SET prefs=? WHERE user_id=?", (json.dumps({"calendar": "jalali"}), self.uid))
        self.assertTrue(app.tg_brief_text(self.db, self.ctx(nxt + 120)).startswith("☀️ <b>شنبه ۳۰ آبان</b>"))
        app.tg_brief(self.db, self.bot, self.ctx(nxt + 86400 + 120, brief=False))
        app.tg_brief(self.db, self.bot, self.ctx(nxt + 2 * 86400 + 5 * 3600, brief=True))
        self.assertEqual(len(self.bot.sent), 1)

    def test_timer_nudge_not_at_night(self):
        start = self.at(2026, 11, 20, 9)
        self.db.execute("INSERT INTO time_entries (user_id, description, start_ts) VALUES (?,?,?)",
                        (self.uid, "Deep work", start))
        app.tg_timer_nudge(self.db, self.bot, self.ctx(start + 3 * 3600))
        self.assertEqual(self.bot.sent, [])
        app.tg_timer_nudge(self.db, self.bot, self.ctx(start + 4 * 3600 + 60))
        app.tg_timer_nudge(self.db, self.bot, self.ctx(start + 5 * 3600))
        self.assertEqual(len(self.bot.sent), 1)
        self.assertIn("4 h 1 min", self.bot.texts()[0])
        self.press(self.bot.sent[0][1][0][0]["callback_data"], start + 5 * 3600)
        self.assertIsNone(app.running_entry(self.db, self.uid))
        self.assertTrue(self.bot.closed[0].startswith("■ Stopped at 14:00 · 5 h"))
        # A timer started late at night waits for the morning.
        night = self.at(2026, 11, 20, 22)
        self.db.execute("INSERT INTO time_entries (user_id, start_ts) VALUES (?,?)", (self.uid, night))
        app.tg_timer_nudge(self.db, self.bot, self.ctx(night + 5 * 3600))
        self.assertEqual(len(self.bot.sent), 1)
        app.tg_timer_nudge(self.db, self.bot, self.ctx(night + 9 * 3600 + 60))
        self.assertEqual(len(self.bot.sent), 2)

    def test_remind_parsing(self):
        x = self.ctx(self.at(2026, 11, 20, 17))
        self.assertEqual(app.parse_remind(x, "18:30 Call mom"), (self.at(2026, 11, 20, 18, 30), "Call mom"))
        self.assertEqual(app.parse_remind(x, "9:00 Gym"), (self.at(2026, 11, 21, 9), "Gym"))  # passed: tomorrow
        self.assertEqual(app.parse_remind(x, "tomorrow 18:30 Dentist"), (self.at(2026, 11, 21, 18, 30), "Dentist"))
        self.assertEqual(app.parse_remind(x, "20m Tea"), (x.now + 1200, "Tea"))
        self.assertEqual(app.parse_remind(x, "2 hours Stretch"), (x.now + 7200, "Stretch"))
        self.assertEqual(app.parse_remind(x, "۱۸:۳۰ تماس با مامان"), (self.at(2026, 11, 20, 18, 30), "تماس با مامان"))
        for bad in ("25:00 x", "18:30", "soon Tea", "tomorrow 20m Tea", "0m Tea"):
            self.assertIsNone(app.parse_remind(x, bad), bad)
        self.assertEqual(x.when(self.at(2026, 11, 21, 9)), "tomorrow 09:00")
        self.assertEqual(x.when(self.at(2026, 11, 24, 9)), "Tuesday 09:00")
        self.assertEqual(x.when(self.at(2026, 12, 3, 9)), "3 Dec 09:00")

    def test_commands(self):
        x = self.ctx(self.at(2026, 11, 20, 10))
        msg = lambda text: {"message_id": 1, "chat": {"id": 555}, "text": text}  # noqa: E731
        app.tg_on_message(self.db, self.bot, x, msg("/todo"))
        self.assertIn("No open to-dos", self.bot.texts()[-1])
        self.task("One", due="2026-11-20")
        app.tg_on_message(self.db, self.bot, x, msg("/todo@daybook_test_bot"))
        self.assertEqual(self.bot.texts()[-1], "<b>To-do</b> · 1 open\n• One")
        app.tg_on_message(self.db, self.bot, x, msg("/today"))
        self.assertTrue(self.bot.texts()[-1].startswith("<b>Friday 20 November</b>"))
        app.tg_on_message(self.db, self.bot, x, msg("/timer"))
        self.assertEqual(self.bot.texts()[-1], "No timer running.")
        app.tg_on_message(self.db, self.bot, x, msg("/stop"))
        self.assertEqual(self.bot.texts()[-1], "No timer was running.")
        app.tg_on_message(self.db, self.bot, x, msg("/remind whenever"))
        self.assertIn("Say when and what", self.bot.texts()[-1])
        app.tg_on_message(self.db, self.bot, x, msg("/nope"))
        self.assertIn("/help", self.bot.texts()[-1])
        app.tg_on_message(self.db, self.bot, x, {"message_id": 1, "chat": {"id": 555}, "sticker": {}})
        self.assertEqual(len(self.bot.sent), 7)  # a sticker gets no answer
        # Not linked: only /start with the right code links, and a wrong code gets a hint.
        self.db.execute("UPDATE telegram SET chat_id=NULL, link_code='abc123'")
        x = self.ctx(self.at(2026, 11, 20, 10))
        app.tg_on_message(self.db, self.bot, x, {"chat": {"id": 9}, "text": "/start سلام"})
        self.assertIsNone(app.tg_row(self.db, self.uid)["chat_id"])
        app.tg_on_message(self.db, self.bot, x, {"chat": {"id": 9, "first_name": "Sam"}, "text": "/start abc123"})
        self.assertEqual(app.tg_row(self.db, self.uid)["chat_id"], 9)
        self.assertTrue(app.tg_was_sent(self.db, self.uid, "brief:2026-11-20"))  # 10:00 is past 08:00

    def test_proxy_parsing(self):
        p = app.parse_proxy({"host": "socks5://me:p%40ss@10.0.0.2:1080"})
        self.assertEqual(p, {"host": "10.0.0.2", "port": 1080, "username": "me", "password": "p@ss"})
        self.assertEqual(app.parse_proxy({"host": "127.0.0.1", "port": "10808"})["port"], 10808)
        kept = app.parse_proxy({"host": "h.example", "port": 1, "username": "me"}, old={"password": "old"})
        self.assertEqual(kept["password"], "old")
        self.assertIsNone(app.parse_proxy({"host": ""}))
        for bad in ({"host": "http://x:1"}, {"host": "a b", "port": 1}, {"host": "x", "port": 70000}, {"host": "x"}):
            with self.assertRaises(app.ApiError):
                app.parse_proxy(bad)


if __name__ == "__main__":
    unittest.main()
