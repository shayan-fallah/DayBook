"""End-to-end tests: start the real server on a free port with a temp database.

Run:  python3 -m unittest discover -s tests -v
"""
import datetime as dt
import http.cookiejar
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
import app  # noqa: E402

TODAY = dt.date.today().isoformat()
YESTERDAY = (dt.date.today() - dt.timedelta(days=1)).isoformat()


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Client:
    def __init__(self, base):
        self.base = base
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def call(self, method, path, body=None, csrf=True, raw=False):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        if csrf:
            req.add_header("X-Requested-With", "fetch")
        try:
            with self.opener.open(req) as res:
                payload = res.read()
                return res.status, (payload if raw else json.loads(payload or b"null"))
        except urllib.error.HTTPError as e:
            payload = e.read()
            e.close()
            try:
                return e.code, json.loads(payload)
            except ValueError:
                return e.code, payload


class AppTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.port = free_port()
        env = {**os.environ, "DATA_DIR": cls.tmp.name, "PORT": str(cls.port), "HOST": "127.0.0.1"}
        cls.proc = subprocess.Popen([sys.executable, os.path.join(ROOT, "app.py")], env=env,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        cls.base = f"http://127.0.0.1:{cls.port}"
        for _ in range(200):
            try:
                urllib.request.urlopen(cls.base + "/api/me", timeout=1)
                break
            except Exception:
                time.sleep(0.1)
        else:
            raise RuntimeError("server did not start")
        cls.c = Client(cls.base)
        status, _ = cls.c.call("POST", "/api/signup", {"username": "tester", "password": "secret123"})
        assert status == 200, status

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        cls.proc.wait(5)
        cls.proc.stdout.close()
        cls.tmp.cleanup()

    # --- helpers
    def find(self, q):
        status, res = self.c.call("GET", "/api/foods/search?q=" + urllib.request.quote(q))
        self.assertEqual(status, 200)
        return res["results"]

    # --- tests
    def test_normalize(self):
        self.assertEqual(app.normalize("سيب"), "سیب")
        self.assertEqual(app.normalize("كيك"), "کیک")
        self.assertEqual(app.normalize("چای‌خوری"), "چای خوری")
        self.assertEqual(app.normalize("۱۲۳"), "123")
        self.assertEqual(app.normalize("آب"), "اب")
        self.assertEqual(app.normalize("نانِ  (سنگک)"), "نان سنگک")

    def test_targets_formula(self):
        p = {"sex": "male", "age": 30, "height_cm": 180, "activity": "moderate", "rate_kg_week": -0.5,
             "calorie_goal": None, "protein_pct": 25, "carbs_pct": 45, "fat_pct": 30, "water_goal": 8}
        t = app.compute_targets(p, 80)
        # BMR = 800 + 1125 - 150 + 5 = 1780; TDEE = 2759; -550 = 2209 -> 2210
        self.assertEqual(t["bmr"], 1780)
        self.assertEqual(t["kcal"], 2210)
        self.assertEqual(t["protein"], round(2210 * 25 / 400))
        p["calorie_goal"] = 1800
        self.assertEqual(app.compute_targets(p, 80)["kcal"], 1800)
        # Safe floor
        p.update(calorie_goal=None, sex="female", rate_kg_week=-1, activity="sedentary", height_cm=150)
        self.assertEqual(app.compute_targets(p, 45)["kcal"], 1200)

    def test_auth_required_and_csrf(self):
        anon = Client(self.base)
        self.assertEqual(anon.call("GET", "/api/day?date=" + TODAY)[0], 401)
        self.assertEqual(self.c.call("POST", "/api/entries", {"x": 1}, csrf=False)[0], 403)
        # Sign-up is closed once a user exists
        status, _ = anon.call("POST", "/api/signup", {"username": "other", "password": "secret123"})
        self.assertEqual(status, 403)
        status, me = anon.call("GET", "/api/me")
        self.assertFalse(me["signup_allowed"])

    def test_login_logout(self):
        c = Client(self.base)
        self.assertEqual(c.call("POST", "/api/login", {"username": "tester", "password": "nope"})[0], 401)
        self.assertEqual(c.call("POST", "/api/login", {"username": "TESTER", "password": "secret123"})[0], 200)
        self.assertEqual(c.call("GET", "/api/me")[1]["user"], "tester")
        c.call("POST", "/api/logout")
        self.assertIsNone(c.call("GET", "/api/me")[1]["user"])

    def test_search_ranking(self):
        res = self.find("سيب")  # Arabic yeh should still match
        self.assertTrue(res)
        self.assertEqual(res[0]["name"], "سیب")
        self.assertEqual(self.find("   "), [])
        self.assertTrue(all("مرغ" in r["name"] for r in self.find("تخم مرغ")[:5]))
        # LIKE wildcards in the query must not match everything
        self.assertEqual(self.find("%%%"), [])

    def test_food_detail_and_entry_math(self):
        apple = self.find("سیب")[0]
        status, food = self.c.call("GET", f"/api/foods/{apple['id']}")
        self.assertEqual(status, 200)
        names = [u["name"] for u in food["units"]]
        self.assertIn("گرم", names)
        self.assertEqual(names[-1], "گرم")  # grams listed last
        status, res = self.c.call("POST", "/api/entries", {
            "date": TODAY, "meal": "breakfast", "food_id": apple["id"], "unit": "گرم", "qty": 250})
        self.assertEqual(status, 200)
        day = self.c.call("GET", f"/api/day?date={TODAY}")[1]
        e = next(x for x in day["entries"] if x["id"] == res["id"])
        self.assertEqual(e["grams"], 250)
        self.assertAlmostEqual(e["kcal"], round(food["kcal"] * 2.5), delta=1)
        # Edit: change amount, recalculated
        self.c.call("PATCH", f"/api/entries/{res['id']}", {"qty": 100})
        day = self.c.call("GET", f"/api/day?date={TODAY}")[1]
        e = next(x for x in day["entries"] if x["id"] == res["id"])
        self.assertAlmostEqual(e["kcal"], food["kcal"], delta=1)
        # Move meal
        self.c.call("PATCH", f"/api/entries/{res['id']}", {"meal": "snack"})
        day = self.c.call("GET", f"/api/day?date={TODAY}")[1]
        self.assertEqual(next(x for x in day["entries"] if x["id"] == res["id"])["meal"], "snack")
        # Bad input
        self.assertEqual(self.c.call("POST", "/api/entries", {
            "date": TODAY, "meal": "brunch", "food_id": apple["id"], "unit": "گرم", "qty": 1})[0], 400)
        self.assertEqual(self.c.call("POST", "/api/entries", {
            "date": TODAY, "meal": "lunch", "food_id": apple["id"], "unit": "nope", "qty": 1})[0], 400)
        self.assertEqual(self.c.call("POST", "/api/entries", {
            "date": "2024-13-40", "meal": "lunch", "food_id": apple["id"], "unit": "گرم", "qty": 1})[0], 400)
        self.c.call("DELETE", f"/api/entries/{res['id']}")
        day = self.c.call("GET", f"/api/day?date={TODAY}")[1]
        self.assertFalse(any(x["id"] == res["id"] for x in day["entries"]))

    def test_quick_add_copy_and_totals(self):
        d = "2020-01-02"
        self.c.call("POST", "/api/entries", {"date": "2020-01-01", "meal": "lunch", "name": "Pizza", "kcal": 800})
        self.c.call("POST", "/api/entries", {"date": "2020-01-01", "meal": "dinner", "protein": 10, "carbs": 10, "fat": 10})
        status, res = self.c.call("POST", "/api/entries/copy", {"from": "2020-01-01", "to": d, "meal": "lunch"})
        self.assertEqual(res["copied"], 1)
        day = self.c.call("GET", f"/api/day?date={d}")[1]
        self.assertEqual(day["totals"]["kcal"], 800)
        res = self.c.call("POST", "/api/entries/copy", {"from": "2020-01-01", "to": "2020-01-03"})[1]
        self.assertEqual(res["copied"], 2)
        day = self.c.call("GET", "/api/day?date=2020-01-03")[1]
        self.assertEqual(day["totals"]["kcal"], 800 + 170)  # macros-only quick add -> 4/4/9 kcal per g
        quick = next(e for e in day["entries"] if e["meal"] == "lunch")
        self.c.call("PATCH", f"/api/entries/{quick['id']}", {"kcal": 500})
        day = self.c.call("GET", "/api/day?date=2020-01-03")[1]
        self.assertEqual(day["totals"]["kcal"], 670)

    def test_water_exercise_weight(self):
        d = "2021-05-05"
        self.c.call("POST", "/api/days", {"date": d, "water": 5})
        self.c.call("POST", "/api/days", {"date": d, "exercise": 300})
        self.c.call("POST", "/api/weights", {"date": d, "kg": 81.25})
        day = self.c.call("GET", f"/api/day?date={d}")[1]
        self.assertEqual((day["water"], day["exercise"], day["weight"]), (5, 300, 81.25))
        self.assertEqual(self.c.call("POST", "/api/weights", {"date": d, "kg": 5})[0], 400)
        self.c.call("DELETE", f"/api/weights/{d}")
        self.assertIsNone(self.c.call("GET", f"/api/day?date={d}")[1]["weight"])

    def test_custom_food_and_favorites(self):
        status, res = self.c.call("POST", "/api/foods", {
            "name": "Protein bar X", "serving_name": "bar", "serving_grams": 50,
            "kcal": 200, "protein": 20, "carbs": 15, "fat": 7})
        self.assertEqual(status, 200)
        fid = res["id"]
        self.assertGreater(fid, app.CUSTOM_ID_BASE)
        food = self.c.call("GET", f"/api/foods/{fid}")[1]
        self.assertEqual(food["kcal"], 400)  # stored per 100 g
        self.assertEqual(food["units"][0], {"name": "bar", "grams": 50})
        self.assertEqual(self.find("protein bar")[0]["id"], fid)
        e = self.c.call("POST", "/api/entries", {"date": "2022-02-02", "meal": "snack", "food_id": fid,
                                                 "unit": "bar", "qty": 2})[1]
        self.assertEqual(self.c.call("POST", f"/api/favorites/{fid}")[1], {"fav": True})
        quick = self.c.call("GET", "/api/foods/quick")[1]
        self.assertIn(fid, [f["id"] for f in quick["favorites"]])
        self.assertIn(fid, [f["id"] for f in quick["mine"]])
        self.assertEqual(quick["recent"][0]["last"]["unit"], "bar")
        # Update keeps id
        self.c.call("PUT", f"/api/foods/{fid}", {"name": "Protein bar Y", "serving_name": "bar",
                                                 "serving_grams": 50, "kcal": 250})
        self.assertEqual(self.c.call("GET", f"/api/foods/{fid}")[1]["name"], "Protein bar Y")
        # Delete: the log entry survives and can still be resized
        self.c.call("DELETE", f"/api/foods/{fid}")
        self.assertEqual(self.c.call("GET", f"/api/foods/{fid}")[0], 404)
        day = self.c.call("GET", "/api/day?date=2022-02-02")[1]
        self.assertEqual(day["totals"]["kcal"], 400)
        self.assertEqual(self.c.call("PATCH", f"/api/entries/{e['id']}", {"qty": 1})[0], 200)
        day = self.c.call("GET", "/api/day?date=2022-02-02")[1]
        self.assertEqual(day["totals"]["kcal"], 200)

    def test_other_users_cannot_see_data(self):
        # Create a second user directly in the database
        db = sqlite_connect(self.tmp.name)
        app.create_user(db, "intruder", "secret123")
        db.commit()
        db.close()
        other = Client(self.base)
        other.call("POST", "/api/login", {"username": "intruder", "password": "secret123"})
        fid = self.c.call("POST", "/api/foods", {"name": "Secret soup", "serving_grams": 100, "kcal": 50})[1]["id"]
        e = self.c.call("POST", "/api/entries", {"date": "2023-03-03", "meal": "lunch", "name": "mine", "kcal": 1})[1]
        self.assertEqual(other.call("GET", f"/api/foods/{fid}")[0], 404)
        self.assertEqual(other.call("PATCH", f"/api/entries/{e['id']}", {"kcal": 9})[0], 404)
        other.call("DELETE", f"/api/entries/{e['id']}")
        self.assertEqual(len(self.c.call("GET", "/api/day?date=2023-03-03")[1]["entries"]), 1)
        self.assertFalse(any(r["id"] == fid for r in other.call("GET", "/api/foods/search?q=secret")[1]["results"]))

    def test_profile_and_stats(self):
        status, p = self.c.call("PUT", "/api/profile", {
            "sex": "female", "age": 28, "height_cm": 165, "weight": 70, "activity": "light",
            "rate_kg_week": -0.5, "target_weight": 62, "protein_pct": 30, "carbs_pct": 40, "fat_pct": 30,
            "water_goal": 10, "date": TODAY})
        self.assertEqual(status, 200, p)
        self.assertTrue(p["targets"]["suggested"])
        self.assertEqual(p["weight"], 70)
        bad = self.c.call("PUT", "/api/profile", {"protein_pct": 50, "carbs_pct": 50, "fat_pct": 50})
        self.assertEqual(bad[0], 400)
        self.c.call("POST", "/api/entries", {"date": YESTERDAY, "meal": "lunch", "name": "x", "kcal": 1000})
        self.c.call("POST", "/api/entries", {"date": TODAY, "meal": "lunch", "name": "y", "kcal": 900})
        s = self.c.call("GET", f"/api/stats?end={TODAY}&days=7")[1]
        self.assertEqual(len(s["days"]), 7)
        self.assertGreaterEqual(s["streak"], 2)
        self.assertGreaterEqual(s["logged_days"], 2)
        self.assertEqual(s["target_weight"], 62)
        self.assertTrue(s["top_foods"])

    def test_export_and_static(self):
        self.c.call("POST", "/api/entries", {"date": "2019-09-09", "meal": "lunch", "name": "کباب", "kcal": 600})
        status, data = self.c.call("GET", "/api/export?type=entries", raw=True)
        self.assertEqual(status, 200)
        text = data.decode("utf-8-sig")
        self.assertTrue(text.startswith("date,meal,name"))
        self.assertIn("کباب", text)
        status, html = self.c.call("GET", "/", raw=True)
        self.assertEqual(status, 200)
        self.assertIn(b"Daybook", html)
        self.assertEqual(self.c.call("GET", "/static/../app.py", raw=True)[0], 404)
        self.assertEqual(self.c.call("GET", "/static/app.js", raw=True)[0], 200)

    def test_bearer_token_auth(self):
        c = Client(self.base)
        status, res = c.call("POST", "/api/login", {"username": "tester", "password": "secret123", "want_token": True})
        self.assertEqual(status, 200)
        token = res["token"]
        self.assertFalse(list(c.jar), "token logins should not also set a cookie")

        def call(method, path, body=None, tok=token):
            data = json.dumps(body).encode() if body is not None else None
            req = urllib.request.Request(self.base + path, data=data, method=method)
            req.add_header("Content-Type", "application/json")
            if tok:
                req.add_header("Authorization", "Bearer " + tok)
            try:
                with urllib.request.urlopen(req) as r:
                    return r.status, json.loads(r.read())
            except urllib.error.HTTPError as e:
                e.close()
                return e.code, None

        self.assertEqual(call("GET", "/api/me")[1]["user"], "tester")
        # Writes work without the browser CSRF header when a token is used
        self.assertEqual(call("POST", "/api/entries", {"date": "2018-08-08", "meal": "lunch", "kcal": 300})[0], 200)
        self.assertEqual(call("GET", "/api/day?date=2018-08-08", tok="wrong")[0], 401)
        self.assertEqual(call("POST", "/api/logout")[0], 200)
        self.assertEqual(call("GET", "/api/day?date=2018-08-08")[0], 401)

    def test_password_change(self):
        db = sqlite_connect(self.tmp.name)
        app.create_user(db, "pwuser", "secret123")
        db.commit()
        db.close()
        c = Client(self.base)
        c.call("POST", "/api/login", {"username": "pwuser", "password": "secret123"})
        self.assertEqual(c.call("POST", "/api/password", {"current": "bad", "new": "newsecret"})[0], 400)
        self.assertEqual(c.call("POST", "/api/password", {"current": "secret123", "new": "newsecret"})[0], 200)
        self.assertEqual(Client(self.base).call("POST", "/api/login",
                                                {"username": "pwuser", "password": "newsecret"})[0], 200)

    def new_user(self, name):
        db = sqlite_connect(self.tmp.name)
        app.create_user(db, name, "secret123")
        db.commit()
        db.close()
        c = Client(self.base)
        c.call("POST", "/api/login", {"username": name, "password": "secret123"})
        return c

    def test_workouts(self):
        c = self.new_user("lifter")
        d = "2024-05-10"
        c.call("POST", "/api/weights", {"date": "2024-05-01", "kg": 80})
        st, meta = c.call("GET", "/api/workouts/meta")
        self.assertEqual(st, 200)
        self.assertEqual(meta["activities"][0]["key"], "bodybuilding")
        self.assertEqual(meta["weight"], 80)
        # 90 min of hard bodybuilding at 80 kg: (6.0 - 1) * 80 * 1.5 = 600 kcal
        st, w = c.call("POST", "/api/workouts", {
            "date": d, "activity": "bodybuilding", "minutes": 90, "intensity": "hard",
            "muscles": ["triceps", "chest", "chest"], "distance_km": 5, "notes": "Bench 80 kg 5x5"})
        self.assertEqual((st, w["kcal"]), (200, 600))
        st, run = c.call("POST", "/api/workouts", {"date": d, "activity": "running", "minutes": 30,
                                                   "intensity": "moderate", "distance_km": 5, "kcal": 350})
        self.assertEqual(run["kcal"], 350)  # typed calories win over the estimate
        day = c.call("GET", f"/api/day?date={d}")[1]
        self.assertEqual([x["name"] for x in day["workouts"]], ["Bodybuilding", "Running"])
        lift = day["workouts"][0]
        self.assertEqual(lift["muscles"], ["chest", "triceps"])  # deduped, catalog order
        self.assertIsNone(lift["distance_km"])  # no distance for lifting
        self.assertEqual((day["burned"], day["exercise"], day["exercise_pct"]), (950, 950, 100))

        # Editing the duration re-estimates; a typed calorie number stays.
        c.call("PATCH", f"/api/workouts/{w['id']}", {"minutes": 60})
        c.call("PATCH", f"/api/workouts/{run['id']}", {"minutes": 45})
        day = c.call("GET", f"/api/day?date={d}")[1]
        self.assertEqual([x["kcal"] for x in day["workouts"]], [400, 350])
        c.call("PATCH", f"/api/workouts/{run['id']}", {"kcal": None})  # back to the estimate: 8.8*80*0.75
        self.assertEqual(c.call("GET", f"/api/day?date={d}")[1]["workouts"][1]["kcal"], 528)

        # Only half of workout calories go into the daily goal when asked.
        c.call("PUT", "/api/profile", {"exercise_pct": 50, "workout_goal": 3})
        day = c.call("GET", f"/api/day?date={d}")[1]
        self.assertEqual((day["burned"], day["exercise"]), (928, 464))
        self.assertEqual(c.call("GET", f"/api/stats?end={d}&days=7")[1]["days"][-1]["exercise"], 464)
        c.call("PUT", "/api/profile", {"sex": "male"})  # older apps don't send the new fields
        self.assertEqual(c.call("GET", "/api/profile")[1]["exercise_pct"], 50)
        self.assertEqual(c.call("PUT", "/api/profile", {"exercise_pct": 30})[0], 400)

        # Validation
        bad = [{"activity": "flying", "minutes": 30}, {"activity": "running", "minutes": 0},
               {"activity": "other", "minutes": 30}, {"activity": "running", "minutes": 30, "intensity": "max"},
               {"activity": "bodybuilding", "minutes": 30, "muscles": ["wings"]}]
        for body in bad:
            self.assertEqual(c.call("POST", "/api/workouts", {"date": d, **body})[0], 400, body)
        st, other = c.call("POST", "/api/workouts", {"date": "2024-05-08", "activity": "other", "name": "Climbing",
                                                     "minutes": 60, "muscles": ["back"]})
        self.assertEqual(st, 200)
        self.assertEqual(self.c.call("DELETE", f"/api/workouts/{other['id']}")[0], 200)  # someone else's: no-op
        self.assertEqual(self.c.call("PATCH", f"/api/workouts/{other['id']}", {"minutes": 5})[0], 404)

        # Stats
        c.call("POST", "/api/workouts", {"date": "2024-05-04", "activity": "bodybuilding", "minutes": 60,
                                         "muscles": ["legs"]})
        c.call("POST", "/api/workouts", {"date": "2024-05-02", "activity": "calisthenics", "minutes": 30,
                                         "muscles": ["full_body"]})
        st, s = c.call("GET", f"/api/workouts/stats?end={d}&days=7")
        self.assertEqual(st, 200)
        self.assertEqual(len(s["buckets"]), 7)
        self.assertEqual(s["buckets"][-1], {"start": d, "end": d, "strength": 60, "cardio": 45, "other": 0,
                                            "sessions": 2, "kcal": 928})
        self.assertEqual(s["totals"]["sessions"], 4)
        self.assertEqual(s["totals"]["days"], 3)
        self.assertEqual(s["week"], {"days": 3, "sessions": 4, "goal": 3, "minutes": 225,
                                     "kcal": s["totals"]["kcal"]})
        self.assertEqual(s["goal_streak"], 1)
        m = {x["key"]: x for x in s["muscles"]}
        self.assertEqual(m["chest"]["days_ago"], 0)
        self.assertEqual(m["legs"]["last"], "2024-05-04")
        self.assertEqual(m["abs"]["last"], "2024-05-02")  # from the full-body session
        self.assertEqual(m["abs"]["sessions"], 0)  # outside the 7-day range
        self.assertEqual(s["by_activity"][0]["name"], "Bodybuilding")
        self.assertEqual(s["workouts"][0]["date"], d)
        long = c.call("GET", f"/api/workouts/stats?end={d}&days=90")[1]
        self.assertEqual(long["bucket_days"], 7)
        self.assertEqual(sum(b["sessions"] for b in long["buckets"]), 5)
        self.assertEqual(long["buckets"][-1]["start"], "2024-05-04")

        meta = c.call("GET", "/api/workouts/meta")[1]
        self.assertEqual([x["activity"] for x in meta["recent"]][:3], ["running", "bodybuilding", "other"])
        st, csv_data = c.call("GET", "/api/export?type=workouts", raw=True)
        self.assertIn("Bench 80 kg 5x5", csv_data.decode("utf-8-sig"))
        c.call("DELETE", f"/api/workouts/{w['id']}")
        self.assertEqual(len(c.call("GET", f"/api/day?date={d}")[1]["workouts"]), 1)

    def test_migrate_old_exercise_box(self):
        import sqlite3
        db = sqlite3.connect(":memory:")
        db.row_factory = sqlite3.Row
        db.execute("CREATE TABLE profiles (user_id INTEGER PRIMARY KEY, sex TEXT, age INTEGER, height_cm REAL, "
                   "activity TEXT, rate_kg_week REAL, target_weight REAL, calorie_goal INTEGER, protein_pct INTEGER, "
                   "carbs_pct INTEGER, fat_pct INTEGER, water_goal INTEGER)")
        db.execute("INSERT INTO profiles (user_id) VALUES (1)")
        db.executescript(app.SCHEMA)
        db.execute("INSERT INTO days (user_id, date, water, exercise_kcal) VALUES (1, '2024-01-01', 3, 250)")
        app.migrate(db)
        app.migrate(db)  # running twice changes nothing
        p = db.execute("SELECT exercise_pct, workout_goal FROM profiles").fetchone()
        self.assertEqual(tuple(p), (100, 4))
        rows = db.execute("SELECT date, activity, name, kcal, kcal_manual, minutes FROM workouts").fetchall()
        self.assertEqual([tuple(r) for r in rows], [("2024-01-01", "other", "Exercise", 250, 1, None)])
        self.assertEqual(db.execute("SELECT exercise_kcal, water FROM days").fetchone()[:], (0, 3))


def sqlite_connect(data_dir):
    app.DATA_DIR = data_dir
    return app.connect()


if __name__ == "__main__":
    unittest.main()
