"""Tests for food editing, time tracking, to-dos, the planner, the calendar feed and Google Calendar sync.

Google is replaced by a small fake server, so these run offline.

Run:  python3 -m unittest discover -s tests -v
"""
import datetime as dt
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_app import ROOT, Client, free_port  # noqa: E402

sys.path.insert(0, ROOT)
import app  # noqa: E402

HOUR = 3600


def start_server(test_cls, extra_env=None):
    test_cls.tmp = tempfile.TemporaryDirectory()
    test_cls.port = free_port()
    env = {**os.environ, "DATA_DIR": test_cls.tmp.name, "PORT": str(test_cls.port), "HOST": "127.0.0.1",
           **(extra_env or {})}
    test_cls.proc = subprocess.Popen([sys.executable, os.path.join(ROOT, "app.py")], env=env,
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    test_cls.base = f"http://127.0.0.1:{test_cls.port}"
    for _ in range(200):
        try:
            urllib.request.urlopen(test_cls.base + "/api/me", timeout=1)
            break
        except Exception:
            time.sleep(0.1)
    else:
        raise RuntimeError("server did not start")
    test_cls.c = Client(test_cls.base)
    status, _ = test_cls.c.call("POST", "/api/signup", {"username": "tester", "password": "secret123"})
    assert status == 200, status


def stop_server(test_cls):
    test_cls.proc.terminate()
    test_cls.proc.wait(5)
    test_cls.proc.stdout.close()
    test_cls.tmp.cleanup()


def second_user(test_cls, name="other"):
    app.DATA_DIR = test_cls.tmp.name
    db = app.connect()
    app.create_user(db, name, "secret123")
    db.commit()
    db.close()
    other = Client(test_cls.base)
    other.call("POST", "/api/login", {"username": name, "password": "secret123"})
    return other


class WorkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        start_server(cls)

    @classmethod
    def tearDownClass(cls):
        stop_server(cls)

    def ok(self, method, path, body=None, client=None):
        status, res = (client or self.c).call(method, path, body)
        self.assertEqual(status, 200, res)
        return res

    def search(self, q, client=None):
        return self.ok("GET", "/api/foods/search?q=" + urllib.parse.quote(q), client=client)["results"]

    # ------------------------------------------------------------ foods

    def test_edit_dataset_food_is_personal(self):
        bread = self.search("نان سنگک")[0]
        orig = self.ok("GET", f"/api/foods/{bread['id']}")
        self.assertFalse(orig["edited"])
        e = self.ok("POST", "/api/entries", {"date": "2023-03-03", "meal": "lunch", "food_id": bread["id"],
                                             "unit": "گرم", "qty": 200})
        # New values per 100 g, a renamed food and my own unit
        res = self.ok("PUT", f"/api/foods/{bread['id']}", {
            "name": "Sangak (my bakery)", "basis_grams": 100, "kcal": 300, "protein": 10, "carbs": 60, "fat": 2,
            "units": [{"name": "loaf", "grams": 400}]})
        self.assertEqual(res["updated_entries"], 0)
        food = self.ok("GET", f"/api/foods/{bread['id']}")
        self.assertTrue(food["edited"])
        self.assertEqual((food["name"], food["kcal"], food["protein"]), ("Sangak (my bakery)", 300, 10))
        self.assertEqual(food["original"]["name"], orig["name"])
        self.assertEqual([u["name"] for u in food["units"]], ["loaf", "گرم"])
        self.assertEqual(food["uses"], 1)
        # The old entry keeps its numbers until asked to update
        day = self.ok("GET", "/api/day?date=2023-03-03")
        self.assertAlmostEqual(day["totals"]["kcal"], round(orig["kcal"] * 2), delta=1)
        # Logging uses the new values and units; search finds the new name
        self.ok("POST", "/api/entries", {"date": "2023-03-04", "meal": "lunch", "food_id": bread["id"],
                                         "unit": "loaf", "qty": 0.5})
        self.assertEqual(self.ok("GET", "/api/day?date=2023-03-04")["totals"]["kcal"], 600)
        self.assertEqual(self.search("my bakery")[0]["id"], bread["id"])
        self.assertTrue(self.search("my bakery")[0]["edited"])
        quick = self.ok("GET", "/api/foods/quick")
        self.assertIn(bread["id"], [f["id"] for f in quick["edited"]])
        # Update past entries: the 200 g entry is recalculated
        res = self.ok("PUT", f"/api/foods/{bread['id']}", {
            "name": "Sangak (my bakery)", "basis_grams": 400, "kcal": 1200, "units": [{"name": "loaf", "grams": 400}],
            "update_entries": True})
        self.assertEqual(res["updated_entries"], 2)
        day = self.ok("GET", "/api/day?date=2023-03-03")
        self.assertEqual(day["totals"]["kcal"], 600)
        self.assertEqual(day["entries"][0]["name"], "Sangak (my bakery)")
        # Other users still see the original
        other = second_user(self.__class__, "foodfriend")
        self.assertEqual(self.ok("GET", f"/api/foods/{bread['id']}", client=other)["name"], orig["name"])
        self.assertFalse(self.search("my bakery", client=other))
        # Reset brings the original back (and can recalculate entries)
        res = self.ok("POST", f"/api/foods/{bread['id']}/reset", {"update_entries": True})
        self.assertEqual(res["updated_entries"], 2)
        food = self.ok("GET", f"/api/foods/{bread['id']}")
        self.assertEqual((food["name"], food["edited"]), (orig["name"], False))
        self.assertEqual(self.c.call("POST", f"/api/foods/{bread['id']}/reset", {})[0], 404)
        # The half-loaf entry has a unit the original lacks: it keeps its grams
        day = self.ok("GET", "/api/day?date=2023-03-04")
        self.assertAlmostEqual(day["totals"]["kcal"], round(orig["kcal"] * 2), delta=1)

    def test_edit_units_only_keeps_following_dataset(self):
        apple = self.search("سیب")[0]
        food = self.ok("GET", f"/api/foods/{apple['id']}")
        units = [u for u in food["units"] if u["name"] != "گرم"]
        self.ok("PUT", f"/api/foods/{apple['id']}", {"name": food["name"], "basis_grams": 100, "kcal": 99,
                                                      "units": units})
        app.DATA_DIR = self.tmp.name
        db = app.connect()
        row = db.execute("SELECT units FROM food_overrides WHERE food_id=?", (apple["id"],)).fetchone()
        db.close()
        self.assertIsNone(row[0])  # unchanged units are not copied
        self.ok("POST", f"/api/foods/{apple['id']}/reset", {})

    def test_custom_food_with_many_units(self):
        res = self.ok("POST", "/api/foods", {"name": "Granola", "basis_grams": 50, "kcal": 225, "protein": 5,
                                             "units": [{"name": "cup", "grams": 120}, {"name": "tbsp", "grams": 8},
                                                       {"name": "g", "grams": 1}]})
        food = self.ok("GET", f"/api/foods/{res['id']}")
        self.assertEqual(food["kcal"], 450)
        self.assertEqual([u["name"] for u in food["units"]], ["cup", "tbsp", "گرم"])
        self.assertTrue(food["custom"])
        self.assertEqual(self.c.call("POST", "/api/foods", {"name": "X", "basis_grams": 100,
                                                            "units": [{"name": "a", "grams": 1},
                                                                      {"name": "a", "grams": 2}],
                                                            "kcal": 1})[0], 400)
        self.assertEqual(self.c.call("POST", "/api/foods", {"name": "Empty", "basis_grams": 100, "units": []})[0], 400)
        # Zero-calorie edits are fine (diet soda)
        self.ok("PUT", f"/api/foods/{res['id']}", {"name": "Granola light", "basis_grams": 100, "units": []})

    # ------------------------------------------------------------ projects, tasks, time

    def test_projects_tasks_and_timer(self):
        p = self.ok("POST", "/api/projects", {"name": "Thesis"})
        self.assertRegex(p["color"], r"^#[0-9a-f]{6}$")
        p2 = self.ok("POST", "/api/projects", {"name": "Side gig", "color": "#E0508F"})
        self.assertEqual(p2["color"], "#e0508f")
        self.assertEqual(self.c.call("POST", "/api/projects", {"name": "Bad", "color": "red"})[0], 400)
        t = self.ok("POST", "/api/tasks", {"title": "Write chapter 2", "project_id": p["id"], "due": "2030-01-05",
                                           "priority": 3, "notes": "line 1\nline 2"})
        self.assertEqual((t["due"], t["priority"], t["notes"]), ("2030-01-05", 3, "line 1\nline 2"))
        self.assertEqual(self.c.call("POST", "/api/tasks", {"title": "x", "due": "2030-02-31"})[0], 400)
        loose = self.ok("POST", "/api/tasks", {"title": "Buy milk"})
        self.assertIsNone(loose["project_id"])

        # Timer: the task brings its project along
        run = self.ok("POST", "/api/time/timer/start", {"task_id": t["id"], "description": "Intro"})["running"]
        self.assertEqual((run["project_id"], run["end_ts"]), (p["id"], None))
        self.assertEqual(self.c.call("POST", "/api/time/timer/start",
                                     {"task_id": t["id"], "project_id": p2["id"]})[0], 400)
        # Starting another one stops the first
        run2 = self.ok("POST", "/api/time/timer/start", {"project_id": p2["id"]})["running"]
        self.assertNotEqual(run2["id"], run["id"])
        stopped = self.ok("POST", "/api/time/timer/stop")["stopped"]
        self.assertEqual(stopped["id"], run2["id"])
        self.assertGreater(stopped["end_ts"], stopped["start_ts"])
        self.assertIsNone(self.ok("POST", "/api/time/timer/stop")["stopped"])

        # Manual entries and ranges
        base = 1893456000  # 2030-01-01 00:00 UTC
        e = self.ok("POST", "/api/time/entries", {"project_id": p["id"], "task_id": t["id"], "description": "Draft",
                                                  "start_ts": base + 9 * HOUR, "end_ts": base + 11 * HOUR})
        self.assertEqual(self.c.call("POST", "/api/time/entries", {"start_ts": base, "end_ts": base})[0], 400)
        got = self.ok("GET", f"/api/time/entries?from={base}&to={base + 86400}")["entries"]
        self.assertEqual([x["id"] for x in got], [e["id"]])
        self.ok("PATCH", f"/api/time/entries/{e['id']}", {"end_ts": base + 12 * HOUR, "description": "Draft v2"})
        projects = self.ok("GET", "/api/projects")["projects"]
        thesis = next(x for x in projects if x["id"] == p["id"])
        self.assertGreaterEqual(thesis["total"], 3 * HOUR)
        self.assertEqual(thesis["open_tasks"], 1)
        tasks = self.ok("GET", "/api/tasks")["tasks"]
        self.assertGreaterEqual(next(x for x in tasks if x["id"] == t["id"])["total"], 3 * HOUR)

        # Done tasks move to the end and show for a while
        self.ok("PATCH", f"/api/tasks/{t['id']}", {"done": True})
        tasks = self.ok("GET", "/api/tasks")["tasks"]
        self.assertTrue(tasks[-1]["done"])
        self.assertTrue(tasks[-1]["done_at"])
        self.ok("PATCH", f"/api/tasks/{t['id']}", {"done": False, "title": "Write chapter 2 (again)"})

        # Archive, then delete: the time stays, the project's tasks go
        self.ok("PATCH", f"/api/projects/{p['id']}", {"archived": True})
        self.assertTrue(next(x for x in self.ok("GET", "/api/projects")["projects"] if x["id"] == p["id"])["archived"])
        res = self.ok("DELETE", f"/api/projects/{p['id']}")
        self.assertEqual(res["deleted_tasks"], 1)
        got = self.ok("GET", f"/api/time/entries?from={base}&to={base + 86400}")["entries"]
        self.assertEqual((got[0]["project_id"], got[0]["task_id"]), (None, None))
        self.assertIn(loose["id"], [x["id"] for x in self.ok("GET", "/api/tasks")["tasks"]])

        # Others can't touch any of it
        other = second_user(self.__class__, "timefriend")
        self.assertEqual(other.call("PATCH", f"/api/time/entries/{e['id']}", {"description": "x"})[0], 404)
        self.assertEqual(other.call("POST", "/api/time/timer/start", {"project_id": p2["id"]})[0], 404)
        self.assertEqual(other.call("GET", "/api/projects")[1]["projects"], [])
        self.assertEqual(other.call("DELETE", f"/api/tasks/{loose['id']}")[0], 404)

    def test_csv_export_time(self):
        base = 1893456000 + 40 * 86400
        self.ok("POST", "/api/time/entries", {"description": "Export me", "start_ts": base, "end_ts": base + 5400})
        status, data = self.c.call("GET", f"/api/export?type=time&tz=210&from={base - 10}&to={base + 10}", raw=True)
        self.assertEqual(status, 200)
        text = data.decode("utf-8-sig")
        self.assertIn("03:30,05:00,1.5,,,Export me", text)

    # ------------------------------------------------------------ planner

    def test_plan_blocks_and_repeats(self):
        p = self.ok("POST", "/api/projects", {"name": "Deep work"})
        base = 1893456000 + 100 * 86400
        res = self.ok("POST", "/api/plan", {"project_id": p["id"], "start_ts": base, "end_ts": base + 2 * HOUR,
                                            "repeat_starts": [base + 86400, base + 2 * 86400]})
        self.assertEqual(len(res["ids"]), 3)
        blocks = self.ok("GET", f"/api/plan?from={base}&to={base + 3 * 86400}")["blocks"]
        self.assertEqual(len(blocks), 3)
        self.assertEqual(len({b["series"] for b in blocks}), 1)
        self.assertEqual(blocks[2]["end_ts"] - blocks[2]["start_ts"], 2 * HOUR)
        self.assertEqual(self.c.call("POST", "/api/plan", {"start_ts": base, "end_ts": base + HOUR})[0], 400)
        self.assertEqual(self.c.call("POST", "/api/plan", {"title": "x", "start_ts": base, "end_ts": base - 1})[0], 400)
        self.ok("PATCH", f"/api/plan/{blocks[0]['id']}", {"title": "Morning focus", "start_ts": base + HOUR,
                                                          "end_ts": base + 3 * HOUR})
        # Delete this and the following ones of the series
        self.assertEqual(self.ok("DELETE", f"/api/plan/{blocks[1]['id']}?following=1")["deleted"], 2)
        blocks = self.ok("GET", f"/api/plan?from={base}&to={base + 3 * 86400}")["blocks"]
        self.assertEqual([b["title"] for b in blocks], ["Morning focus"])

    def test_prefs(self):
        self.assertEqual(self.ok("GET", "/api/prefs"), {"work_goal": 8, "week_start": "auto", "calendar": "gregorian"})
        p = self.ok("PUT", "/api/prefs", {"work_goal": 6.5, "week_start": "sat", "calendar": "jalali"})
        self.assertEqual(p, {"work_goal": 6.5, "week_start": "sat", "calendar": "jalali"})
        self.assertEqual(self.c.call("PUT", "/api/prefs", {"week_start": "tue"})[0], 400)
        self.assertEqual(self.ok("GET", "/api/prefs")["calendar"], "jalali")

    def test_ics_feed(self):
        t = self.ok("POST", "/api/tasks", {"title": "Pay rent, now", "due": "2031-02-01"})
        now = int(time.time())
        self.ok("POST", "/api/plan", {"title": "Feed; test", "start_ts": now + 86400, "end_ts": now + 86400 + HOUR})
        url = self.ok("GET", "/api/ics")["url"]
        path = urllib.parse.urlparse(url).path
        with urllib.request.urlopen(self.base + path) as res:
            self.assertTrue(res.headers["Content-Type"].startswith("text/calendar"))
            body = res.read().decode("utf-8")
        self.assertIn("SUMMARY:Feed\\; test", body)
        self.assertIn("SUMMARY:☐ Pay rent\\, now", body)
        self.assertIn("DTSTART;VALUE=DATE:20310201", body)
        self.assertTrue(all(len(line.encode()) <= 75 for line in body.split("\r\n")))
        new = self.ok("POST", "/api/ics/reset")["url"]
        self.assertNotEqual(new, url)
        self.assertEqual(self.c.call("GET", path, raw=True)[0], 404)
        self.ok("DELETE", f"/api/tasks/{t['id']}")

    def test_import_sakura(self):
        src = os.path.join(self.tmp.name, "logger.db")
        db = sqlite3.connect(src)
        db.executescript("""
            CREATE TABLE projects(id INTEGER PRIMARY KEY, name TEXT, color TEXT, archived INTEGER, created_at INTEGER);
            CREATE TABLE tasks(id INTEGER PRIMARY KEY, project_id INTEGER, name TEXT, done INTEGER, created_at INTEGER);
            CREATE TABLE entries(id INTEGER PRIMARY KEY, project_id INTEGER, task_id INTEGER, description TEXT,
                                 start_ts INTEGER, end_ts INTEGER);
            INSERT INTO projects VALUES (1, 'Sakura proj', '#e0508f', 0, 1700000000);
            INSERT INTO tasks VALUES (1, 1, 'Old task', 1, 1700000000);
            INSERT INTO entries VALUES (1, 1, 1, 'worked', 1700000000, 1700003600);
            INSERT INTO entries VALUES (2, NULL, NULL, '', 1700010000, 1700011800);
        """)
        db.commit()
        db.close()
        env = {**os.environ, "DATA_DIR": self.tmp.name}
        for _ in range(2):  # the second run finds everything already there
            out = subprocess.run([sys.executable, os.path.join(ROOT, "app.py"), "import-sakura", src, "tester"],
                                 env=env, capture_output=True, text=True, timeout=60)
            self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn("0 projects, 0 tasks and 0 time entries", out.stdout)
        got = self.ok("GET", "/api/time/entries?from=1699990000&to=1700020000")["entries"]
        self.assertEqual(len(got), 2)
        proj = next(p for p in self.ok("GET", "/api/projects")["projects"] if p["name"] == "Sakura proj")
        self.assertEqual(proj["total"], 3600)


# ---------------------------------------------------------------- fake Google

class FakeGoogle:
    """Just enough of Google's OAuth and Calendar APIs for the sync code."""

    def __init__(self):
        self.events = {}  # calendar id -> {event id: event}
        self.seq = 0
        self.lock = threading.Lock()
        self.revoked = []
        self.refresh_ok = True
        fake = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def reply(self, status, obj=None):
                data = json.dumps(obj).encode() if obj is not None else b""
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def body(self):
                n = int(self.headers.get("Content-Length") or 0)
                return self.rfile.read(n) if n else b""

            def do_POST(self):
                with fake.lock:
                    fake.handle(self, "POST")

            def do_GET(self):
                with fake.lock:
                    fake.handle(self, "GET")

            def do_PUT(self):
                with fake.lock:
                    fake.handle(self, "PUT")

            def do_DELETE(self):
                with fake.lock:
                    fake.handle(self, "DELETE")

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def stamp(self):
        self.seq += 1
        return f"2026-01-01T{self.seq // 3600:02d}:{self.seq // 60 % 60:02d}:{self.seq % 60:02d}.000Z"

    def handle(self, h, method):
        url = urllib.parse.urlparse(h.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(url.query).items()}
        path = url.path
        if path == "/token":
            form = {k: v[0] for k, v in urllib.parse.parse_qs(h.body().decode()).items()}
            if form["grant_type"] == "authorization_code":
                return h.reply(200, {"access_token": "at-1", "refresh_token": "rt-1", "expires_in": 3600})
            if not self.refresh_ok:
                return h.reply(400, {"error": "invalid_grant", "error_description": "Token has been revoked."})
            return h.reply(200, {"access_token": "at-2", "expires_in": 3600})
        if path == "/revoke":
            self.revoked.append(h.body().decode())
            return h.reply(200, {})
        if not h.headers.get("Authorization", "").startswith("Bearer at-"):
            return h.reply(401, {"error": {"message": "Invalid Credentials"}})
        p = path[len("/calendar/v3"):]
        if p == "/calendars/primary":
            return h.reply(200, {"id": "me@example.com"})
        if p == "/users/me/calendarList":
            return h.reply(200, {"items": [
                {"id": "me@example.com", "summary": "Me", "primary": True, "selected": True, "accessRole": "owner",
                 "backgroundColor": "#9fe1e7"},
                {"id": "team", "summary": "Team", "selected": True, "accessRole": "writer", "backgroundColor": "#f00"},
                {"id": "hidden", "summary": "Hidden", "selected": False, "accessRole": "reader"}]})
        parts = p.strip("/").split("/")  # calendars/{cal}/events[/{id}]
        cal = urllib.parse.unquote(parts[1])
        store = self.events.setdefault(cal, {})
        if len(parts) == 3 and method == "POST":
            ev = json.loads(h.body())
            self.seq += 1
            ev.update(id=f"ev{self.seq}", status="confirmed", updated=self.stamp())
            store[ev["id"]] = ev
            return h.reply(200, ev)
        if len(parts) == 3 and method == "GET":
            lo, hi = q["timeMin"], q["timeMax"]
            items = [e for e in store.values()
                     if (e["status"] != "cancelled" or q.get("showDeleted") == "true")
                     and e["start"].get("dateTime", e["start"].get("date", "")) < hi
                     and e["end"].get("dateTime", e["end"].get("date", "")) > lo]
            return h.reply(200, {"items": items})
        eid = urllib.parse.unquote(parts[3])
        ev = store.get(eid)
        if ev is None:
            return h.reply(404, {"error": {"message": "Not Found"}})
        if method == "PUT":
            if ev["status"] == "cancelled":
                return h.reply(410, {"error": {"message": "Gone"}})
            new = json.loads(h.body())
            new.update(id=eid, status="confirmed", updated=self.stamp())
            store[eid] = new
            return h.reply(200, new)
        if method == "DELETE":
            if ev["status"] == "cancelled":
                return h.reply(410, {"error": {"message": "Gone"}})
            ev.update(status="cancelled", updated=self.stamp())
            return h.reply(204)
        return h.reply(400, {"error": {"message": "unsupported"}})

    def live(self, cal="me@example.com"):
        return [e for e in self.events.get(cal, {}).values() if e["status"] != "cancelled"]

    def add_foreign(self, cal, title, start, end):
        with self.lock:
            self.seq += 1
            ev = {"id": f"x{self.seq}", "summary": title, "status": "confirmed", "updated": self.stamp(),
                  "start": {"dateTime": app.rfc3339(start)}, "end": {"dateTime": app.rfc3339(end)}}
            self.events.setdefault(cal, {})[ev["id"]] = ev
            return ev


class GoogleSyncTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.g = FakeGoogle()
        start_server(cls, {
            "GOOGLE_CLIENT_ID": "cid", "GOOGLE_CLIENT_SECRET": "secret", "PUBLIC_URL": "https://tracker.test",
            "GOOGLE_AUTH_URL": cls.g.url + "/auth", "GOOGLE_TOKEN_URL": cls.g.url + "/token",
            "GOOGLE_REVOKE_URL": cls.g.url + "/revoke", "GOOGLE_API_URL": cls.g.url + "/calendar/v3"})

    @classmethod
    def tearDownClass(cls):
        stop_server(cls)
        cls.g.server.shutdown()

    def ok(self, method, path, body=None):
        status, res = self.c.call(method, path, body)
        self.assertEqual(status, 200, res)
        return res

    def connect(self):
        url = self.ok("POST", "/api/google/connect")["url"]
        q = dict(urllib.parse.parse_qsl(urllib.parse.urlparse(url).query))
        self.assertEqual(q["redirect_uri"], "https://tracker.test/api/google/callback")
        self.assertEqual(q["access_type"], "offline")
        status, page = self.c.call("GET", f"/api/google/callback?code=abc&state={q['state']}", raw=True)
        self.assertEqual(status, 200)
        self.assertIn(b"connected", page)
        # The state works only once
        status, page = self.c.call("GET", f"/api/google/callback?code=abc&state={q['state']}", raw=True)
        self.assertIn(b"expired", page)

    def test_full_sync_cycle(self):
        st = self.ok("GET", "/api/google")
        self.assertEqual((st["available"], st["connected"]), (True, False))
        p = self.ok("POST", "/api/projects", {"name": "Research", "color": "#1baf7a"})
        now = int(time.time())
        start = now - now % HOUR + 2 * 86400
        b1 = self.ok("POST", "/api/plan", {"project_id": p["id"], "notes": "Read papers",
                                           "start_ts": start, "end_ts": start + 2 * HOUR})["block"]
        self.connect()
        st = self.ok("GET", "/api/google")
        self.assertEqual((st["connected"], st["email"], st["calendar_id"]), (True, "me@example.com", "me@example.com"))

        self.ok("POST", "/api/google/sync")
        live = self.g.live()
        self.assertEqual(len(live), 1)
        ev = live[0]
        self.assertEqual(ev["summary"], "Research")  # no title: the project name
        self.assertIn("Read papers", ev["description"])
        self.assertEqual(ev["colorId"], "2")  # Google's "sage" is nearest to the project's green
        self.assertEqual(ev["start"]["dateTime"], app.rfc3339(start))
        block = self.ok("GET", f"/api/plan?from={start}&to={start + HOUR}")["blocks"][0]
        self.assertEqual(block["gcal"], "synced")

        # Edit in the app -> updated in Google (same event)
        self.ok("PATCH", f"/api/plan/{b1['id']}", {"title": "Lit review"})
        self.assertEqual(self.ok("GET", "/api/google")["pending"], 1)
        self.ok("POST", "/api/google/sync")
        self.assertEqual([e["summary"] for e in self.g.live()], ["Lit review"])

        # Someone else's event shows up; ours doesn't show twice
        self.g.add_foreign("team", "Standup", start + 3 * HOUR, start + 3 * HOUR + 900)
        self.g.add_foreign("hidden", "Secret", start, start + HOUR)
        res = self.ok("GET", f"/api/google/events?from={start - 86400}&to={start + 86400}")
        self.assertIsNone(res["error"])
        self.assertEqual([e["title"] for e in res["events"]], ["Standup"])
        self.assertEqual(res["events"][0]["calendar"], "Team")

        # Moved in Google -> the block follows
        with self.g.lock:
            ev = self.g.live()[0]
            ev["start"] = {"dateTime": app.rfc3339(start + HOUR)}
            ev["end"] = {"dateTime": app.rfc3339(start + 4 * HOUR)}
            ev["updated"] = "2099-01-01T00:00:00.000Z"
        res = self.ok("GET", f"/api/google/events?from={start - 86400}&to={start + 86400}")
        self.assertEqual(res["changed"], 1)
        block = self.ok("GET", f"/api/plan?from={start - 86400}&to={start + 86400}")["blocks"][0]
        self.assertEqual((block["start_ts"], block["end_ts"], block["title"]), (start + HOUR, start + 4 * HOUR,
                                                                              "Lit review"))
        self.assertEqual(self.ok("GET", f"/api/google/events?from={start - 86400}&to={start + 86400}")["changed"], 0)

        # Repeating blocks, then delete one in the app -> deleted in Google
        res = self.ok("POST", "/api/plan", {"title": "Gym", "start_ts": start + 10 * HOUR,
                                            "end_ts": start + 11 * HOUR, "repeat_starts": [start + 34 * HOUR]})
        self.ok("POST", "/api/google/sync")
        self.assertEqual(len(self.g.live()), 3)
        self.ok("DELETE", f"/api/plan/{res['ids'][0]}?following=1")
        self.ok("POST", "/api/google/sync")
        self.assertEqual([e["summary"] for e in self.g.live()], ["Lit review"])

        # Deleted in Google -> gone here too
        with self.g.lock:
            ev = self.g.live()[0]
            ev.update(status="cancelled", updated="2099-01-02T00:00:00.000Z")
        self.assertEqual(self.ok("GET", f"/api/google/events?from={start - 86400}&to={start + 86400}")["changed"], 1)
        self.assertEqual(self.ok("GET", f"/api/plan?from={start - 86400}&to={start + 86400}")["blocks"], [])

        # Switch to another calendar: blocks move there
        self.ok("POST", "/api/plan", {"title": "Write", "start_ts": start, "end_ts": start + HOUR})
        self.ok("POST", "/api/google/sync")
        self.assertEqual(len(self.g.live()), 1)
        self.assertEqual(self.c.call("PUT", "/api/google", {"calendar_id": "hidden"})[0], 400)  # read-only
        self.ok("PUT", "/api/google", {"calendar_id": "team", "show_events": False})
        self.ok("POST", "/api/google/sync")
        self.assertEqual(len(self.g.live()), 0)
        self.assertEqual([e["summary"] for e in self.g.live("team")], ["Standup", "Write"])
        res = self.ok("GET", f"/api/google/events?from={start - 86400}&to={start + 86400}")
        self.assertEqual(res["events"], [])  # show_events off

        # Access revoked in Google -> asks to reconnect
        app.DATA_DIR = self.tmp.name
        db = app.connect()
        db.execute("UPDATE google_accounts SET access_expires=0")
        db.commit()
        db.close()
        self.g.refresh_ok = False
        self.ok("POST", "/api/plan", {"title": "Later", "start_ts": start + 5 * HOUR, "end_ts": start + 6 * HOUR})
        self.assertEqual(self.c.call("POST", "/api/google/sync")[0], 400)
        st = self.ok("GET", "/api/google")
        self.assertTrue(st["needs_reauth"])
        self.g.refresh_ok = True
        self.connect()
        self.assertFalse(self.ok("GET", "/api/google")["needs_reauth"])
        self.ok("POST", "/api/google/sync")
        self.assertIn("Later", [e["summary"] for e in self.g.live("team")])

        # Disconnect revokes the token
        self.ok("DELETE", "/api/google")
        self.assertTrue(any("rt-1" in r for r in self.g.revoked))
        self.assertFalse(self.ok("GET", "/api/google")["connected"])


if __name__ == "__main__":
    unittest.main()
