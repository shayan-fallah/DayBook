#!/usr/bin/env python3
"""Calorie tracker: a small self-hosted web app.

Python 3.9+ standard library only. Data lives in one SQLite file.

    python3 app.py                 # run the server
    python3 app.py adduser NAME    # create an account
    python3 app.py passwd NAME     # reset a password
    python3 app.py users           # list accounts
    python3 app.py backup FILE     # safe copy of the database (works while running)
"""
import csv
import datetime as dt
import getpass
import hashlib
import hmac
import io
import json
import mimetypes
import os
import re
import secrets
import sqlite3
import sys
import threading
import time
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

BASE = os.path.dirname(os.path.abspath(__file__))
HOST = os.environ.get("HOST", "0.0.0.0")
PORT = int(os.environ.get("PORT", "8080"))
DATA_DIR = os.environ.get("DATA_DIR", os.path.join(BASE, "data"))
DATASET_DIR = os.environ.get("DATASET_DIR", BASE)
STATIC_DIR = os.path.join(BASE, "static")
ALLOW_SIGNUP = os.environ.get("ALLOW_SIGNUP", "0") == "1"
TRUST_PROXY = os.environ.get("TRUST_PROXY", "0") == "1"
COOKIE_SECURE = os.environ.get("COOKIE_SECURE", "auto")  # auto | 1 | 0

COOKIE_NAME = "ct_session"
SESSION_DAYS = 180
CUSTOM_ID_BASE = 10**12  # custom food ids start here, far above dataset ids
MAX_BODY = 1 << 20
MEALS = ("breakfast", "lunch", "dinner", "snack")
NUTRIENTS = ("kcal", "protein", "carbs", "fat", "fiber", "sugar", "sodium")
ACTIVITY = {"sedentary": 1.2, "light": 1.375, "moderate": 1.55, "active": 1.725, "very": 1.9}
SOURCE_RANK = {"کرفس": 1, "مانکن": 1}  # generic foods first, branded products (زیره) after
GRAM_UNIT = "گرم"

# Workouts. MET values (energy use as a multiple of resting) for easy / moderate / hard effort,
# from the Compendium of Physical Activities. Calories are net: (MET - 1) x kg x hours, because
# resting calories are already part of the daily goal.
ACTIVITIES = [
    # key, name, kind, METs, tracks distance
    ("bodybuilding", "Bodybuilding", "strength", (3.5, 5.0, 6.0), False),
    ("calisthenics", "Calisthenics", "strength", (3.8, 5.0, 8.0), False),
    ("running", "Running", "cardio", (8.0, 9.8, 11.5), True),
    ("walking", "Walking", "cardio", (3.0, 3.8, 5.0), True),
    ("incline_walk", "Incline walk", "cardio", (5.0, 6.5, 8.0), True),
    ("cycling", "Cycling", "cardio", (4.0, 6.8, 8.8), True),
    ("elliptical", "Elliptical", "cardio", (4.5, 5.5, 7.0), False),
    ("rowing", "Rowing machine", "cardio", (4.8, 7.0, 8.5), True),
    ("stairs", "Stair climber", "cardio", (5.0, 7.0, 9.0), False),
    ("hiit", "HIIT / circuit", "cardio", (5.0, 8.0, 9.5), False),
    ("jump_rope", "Jump rope", "cardio", (8.8, 11.0, 12.3), False),
    ("swimming", "Swimming", "cardio", (5.8, 7.0, 9.8), True),
    ("hiking", "Hiking", "cardio", (5.3, 6.0, 7.8), True),
    ("football", "Football", "other", (7.0, 8.0, 10.0), False),
    ("basketball", "Basketball", "other", (6.0, 6.5, 8.0), False),
    ("martial_arts", "Boxing / martial arts", "other", (5.5, 7.8, 10.3), False),
    ("yoga", "Yoga / stretching", "other", (2.3, 2.8, 4.0), False),
    ("other", "Other", "other", (3.5, 5.0, 7.0), False),
]
ACTIVITY_BY_KEY = {a[0]: a for a in ACTIVITIES}
INTENSITIES = (("light", "Easy"), ("moderate", "Moderate"), ("hard", "Hard"))
INTENSITY_INDEX = {k: i for i, (k, _) in enumerate(INTENSITIES)}
MUSCLES = (("chest", "Chest"), ("back", "Back"), ("shoulders", "Shoulders"), ("biceps", "Biceps"),
           ("triceps", "Triceps"), ("legs", "Legs"), ("abs", "Abs"), ("full_body", "Full body"))
MUSCLE_KEYS = [k for k, _ in MUSCLES]
DEFAULT_WEIGHT = 70


def db_path():
    return os.path.join(DATA_DIR, "tracker.db")


# ---------------------------------------------------------------- text search

_DIGITS = {ord(c): str(i) for i, c in enumerate("۰۱۲۳۴۵۶۷۸۹")}
_DIGITS.update({ord(c): str(i) for i, c in enumerate("٠١٢٣٤٥٦٧٨٩")})
_TRANS = str.maketrans({
    **_DIGITS,
    "ي": "ی", "ى": "ی", "ئ": "ی", "ك": "ک", "ة": "ه", "ۀ": "ه",
    "أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ؤ": "و",
    "‌": " ", "‍": "", "ـ": "",
})
_DIACRITICS = re.compile(r"[ً-ٰٟ]")
_NON_WORD = re.compile(r"[^\w\s]|_")


def normalize(text):
    """Fold Persian/Arabic letter variants, digits, diacritics and punctuation."""
    s = _DIACRITICS.sub("", str(text).translate(_TRANS)).lower()
    return " ".join(_NON_WORD.sub(" ", s).split())


# ---------------------------------------------------------------- database

SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY, username TEXT UNIQUE NOT NULL COLLATE NOCASE,
    pw_hash TEXT NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS profiles (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    sex TEXT, age INTEGER, height_cm REAL, activity TEXT DEFAULT 'light',
    rate_kg_week REAL DEFAULT 0, target_weight REAL, calorie_goal INTEGER,
    protein_pct INTEGER DEFAULT 25, carbs_pct INTEGER DEFAULT 45, fat_pct INTEGER DEFAULT 30,
    water_goal INTEGER DEFAULT 8, exercise_pct INTEGER DEFAULT 100, workout_goal INTEGER DEFAULT 4);
CREATE TABLE IF NOT EXISTS foods (
    id INTEGER PRIMARY KEY, user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL, norm TEXT NOT NULL, compact TEXT NOT NULL, source TEXT, rank INTEGER NOT NULL,
    kcal REAL, protein REAL, carbs REAL, fat REAL, fiber REAL, sugar REAL, sodium REAL);
CREATE INDEX IF NOT EXISTS foods_user ON foods(user_id);
CREATE TABLE IF NOT EXISTS units (
    food_id INTEGER NOT NULL REFERENCES foods(id) ON DELETE CASCADE,
    name TEXT NOT NULL, grams REAL NOT NULL, ord INTEGER NOT NULL,
    PRIMARY KEY (food_id, name));
CREATE TABLE IF NOT EXISTS favorites (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    food_id INTEGER NOT NULL, PRIMARY KEY (user_id, food_id));
CREATE TABLE IF NOT EXISTS entries (
    id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    date TEXT NOT NULL, meal TEXT NOT NULL, food_id INTEGER, name TEXT NOT NULL,
    unit TEXT, qty REAL, grams REAL,
    kcal REAL NOT NULL DEFAULT 0, protein REAL NOT NULL DEFAULT 0, carbs REAL NOT NULL DEFAULT 0,
    fat REAL NOT NULL DEFAULT 0, fiber REAL NOT NULL DEFAULT 0, sugar REAL NOT NULL DEFAULT 0,
    sodium REAL NOT NULL DEFAULT 0, created_at TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS entries_user_date ON entries(user_id, date);
CREATE INDEX IF NOT EXISTS entries_user_food ON entries(user_id, food_id);
CREATE TABLE IF NOT EXISTS days (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, date TEXT NOT NULL,
    water INTEGER NOT NULL DEFAULT 0, exercise_kcal INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, date));
CREATE TABLE IF NOT EXISTS weights (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, date TEXT NOT NULL,
    kg REAL NOT NULL, PRIMARY KEY (user_id, date));
CREATE TABLE IF NOT EXISTS workouts (
    id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    date TEXT NOT NULL, activity TEXT NOT NULL, name TEXT, minutes INTEGER,
    intensity TEXT NOT NULL DEFAULT 'moderate', muscles TEXT NOT NULL DEFAULT '', distance_km REAL,
    kcal INTEGER NOT NULL DEFAULT 0, kcal_manual INTEGER NOT NULL DEFAULT 0,
    notes TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS workouts_user_date ON workouts(user_id, date);
"""


def connect():
    db = sqlite3.connect(db_path(), timeout=15)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys = ON")
    db.execute("PRAGMA busy_timeout = 15000")
    return db


def init_db(verbose=True):
    os.makedirs(DATA_DIR, exist_ok=True)
    db = connect()
    db.execute("PRAGMA journal_mode = WAL")
    db.executescript(SCHEMA)
    migrate(db)
    import_dataset(db, verbose)
    db.close()


def migrate(db):
    """Bring databases from older versions up to date."""
    cols = {r[1] for r in db.execute("PRAGMA table_info(profiles)")}
    for name, ddl in (("exercise_pct", "INTEGER DEFAULT 100"), ("workout_goal", "INTEGER DEFAULT 4")):
        if name not in cols:
            db.execute(f"ALTER TABLE profiles ADD COLUMN {name} {ddl}")
    # Calories typed into the old "Exercise" box become workouts, so there is one place for them.
    for r in db.execute("SELECT user_id, date, exercise_kcal FROM days WHERE exercise_kcal > 0").fetchall():
        db.execute("INSERT INTO workouts (user_id, date, activity, name, kcal, kcal_manual, created_at) "
                   "VALUES (?,?,'other','Exercise',?,1,?)", (r[0], r[1], r[2], now_iso()))
    db.execute("UPDATE days SET exercise_kcal=0 WHERE exercise_kcal > 0")
    db.commit()


def _num(value):
    try:
        f = float(value)
        return f if f == f and f >= 0 else 0.0
    except (TypeError, ValueError):
        return 0.0


def import_dataset(db, verbose=True):
    """Load foods.csv/food_units.csv into SQLite; re-imports only when the files change."""
    foods_csv = os.path.join(DATASET_DIR, "foods.csv")
    units_csv = os.path.join(DATASET_DIR, "food_units.csv")
    if not (os.path.exists(foods_csv) and os.path.exists(units_csv)):
        if verbose:
            print(f"warning: dataset not found in {DATASET_DIR}", file=sys.stderr)
        return
    stamp = "|".join(f"{os.path.getsize(p)}:{int(os.path.getmtime(p))}" for p in (foods_csv, units_csv))
    row = db.execute("SELECT value FROM meta WHERE key='dataset'").fetchone()
    if row and row["value"] == stamp:
        return
    t0 = time.time()
    with db:
        db.execute("DELETE FROM units WHERE food_id IN (SELECT id FROM foods WHERE user_id IS NULL)")
        db.execute("DELETE FROM foods WHERE user_id IS NULL")
        with open(foods_csv, encoding="utf-8-sig", newline="") as fh:
            foods = []
            for r in csv.DictReader(fh):
                try:
                    fid = int(r["id"])
                except (KeyError, ValueError):
                    continue
                name = (r.get("name") or "").strip()
                if not name:
                    continue
                norm = normalize(name)
                src = (r.get("source") or "").strip()
                foods.append((fid, name, norm, norm.replace(" ", ""), src, SOURCE_RANK.get(src, 2),
                              _num(r.get("calories")), _num(r.get("protein")), _num(r.get("carbs")),
                              _num(r.get("fat")), _num(r.get("fiber")), _num(r.get("sugar")),
                              _num(r.get("sodium"))))
        db.executemany("INSERT OR REPLACE INTO foods (id, name, norm, compact, source, rank, kcal, protein, "
                       "carbs, fat, fiber, sugar, sodium) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", foods)
        known = {f[0] for f in foods}
        with open(units_csv, encoding="utf-8-sig", newline="") as fh:
            units = []
            for i, r in enumerate(csv.DictReader(fh)):
                try:
                    fid, mult = int(r["food_id"]), float(r["multiplier"])
                except (KeyError, ValueError):
                    continue
                name = (r.get("name") or "").strip()
                if fid in known and name and mult > 0:
                    # A multiplier is "how many 100 g portions" one unit weighs.
                    units.append((fid, name, round(mult * 100, 3), i))
        db.executemany("INSERT OR IGNORE INTO units (food_id, name, grams, ord) VALUES (?,?,?,?)", units)
        # Every food gets a gram unit so any amount can be logged.
        db.execute("INSERT OR IGNORE INTO units (food_id, name, grams, ord) "
                   "SELECT id, ?, 1, 1000000000 FROM foods WHERE user_id IS NULL", (GRAM_UNIT,))
        db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('dataset', ?)", (stamp,))
    if verbose:
        print(f"imported {len(foods)} foods and {len(units)} units in {time.time() - t0:.1f}s")


# ---------------------------------------------------------------- auth helpers

PBKDF2_ITERS = 310_000


def hash_password(pw):
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", pw.encode(), salt, PBKDF2_ITERS)
    return f"pbkdf2${PBKDF2_ITERS}${salt.hex()}${digest.hex()}"


def check_password(pw, stored):
    try:
        _, iters, salt, digest = stored.split("$")
        calc = hashlib.pbkdf2_hmac("sha256", pw.encode(), bytes.fromhex(salt), int(iters))
        return hmac.compare_digest(calc.hex(), digest)
    except (ValueError, AttributeError):
        return False


def token_hash(token):
    return hashlib.sha256(token.encode()).hexdigest()


def create_user(db, username, password):
    username = (username or "").strip()
    if not re.fullmatch(r"[\w.\-@]{2,40}", username):
        raise ApiError(400, "Username must be 2-40 letters, digits or . - _ @")
    if len(password or "") < 6:
        raise ApiError(400, "Password must be at least 6 characters")
    if db.execute("SELECT 1 FROM users WHERE username=?", (username,)).fetchone():
        raise ApiError(409, "That username is taken")
    cur = db.execute("INSERT INTO users (username, pw_hash, created_at) VALUES (?,?,?)",
                     (username, hash_password(password), now_iso()))
    db.execute("INSERT INTO profiles (user_id) VALUES (?)", (cur.lastrowid,))
    return cur.lastrowid


class LoginLimiter:
    """At most 10 failed logins per client per 15 minutes."""

    def __init__(self, limit=10, window=900):
        self.limit, self.window, self.fails, self.lock = limit, window, {}, threading.Lock()

    def blocked(self, key):
        with self.lock:
            now = time.time()
            recent = [t for t in self.fails.get(key, []) if now - t < self.window]
            self.fails[key] = recent
            return len(recent) >= self.limit

    def fail(self, key):
        with self.lock:
            self.fails.setdefault(key, []).append(time.time())

    def reset(self, key):
        with self.lock:
            self.fails.pop(key, None)


LIMITER = LoginLimiter()


# ---------------------------------------------------------------- misc helpers

class ApiError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status, self.message = status, message


def now_iso():
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def parse_date(value, field="date"):
    if isinstance(value, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        try:
            return dt.date.fromisoformat(value)
        except ValueError:
            pass
    raise ApiError(400, f"Invalid {field}, expected YYYY-MM-DD")


def number(value, field, lo=0.0, hi=1e6, allow_none=False):
    if value is None or value == "":
        if allow_none:
            return None
        raise ApiError(400, f"{field} is required")
    try:
        f = float(value)
    except (TypeError, ValueError):
        raise ApiError(400, f"{field} must be a number")
    if not (lo <= f <= hi):
        raise ApiError(400, f"{field} must be between {lo:g} and {hi:g}")
    return f


def text(value, field, max_len=120, required=True):
    s = " ".join(str(value or "").split())[:max_len]
    if required and not s:
        raise ApiError(400, f"{field} is required")
    return s


def meal_of(value):
    if value not in MEALS:
        raise ApiError(400, "Invalid meal")
    return value


def rnd(x, nd=1):
    return round(x or 0, nd)


def entry_json(r):
    out = {k: r[k] for k in ("id", "date", "meal", "food_id", "name", "unit", "qty", "grams")}
    for k in NUTRIENTS:
        out[k] = rnd(r[k], 0 if k in ("kcal", "sodium") else 1)
    return out


def get_food(db, uid, food_id):
    row = db.execute("SELECT * FROM foods WHERE id=? AND (user_id IS NULL OR user_id=?)",
                     (food_id, uid)).fetchone()
    return row


def food_units(db, food_id):
    return [{"name": r["name"], "grams": r["grams"]}
            for r in db.execute("SELECT name, grams FROM units WHERE food_id=? ORDER BY name='گرم', ord", (food_id,))]


def compute_portion(db, uid, food_id, unit, qty):
    """Nutrition for qty x unit of a food, or None if the food is gone."""
    food = get_food(db, uid, food_id)
    if not food:
        return None
    u = db.execute("SELECT grams FROM units WHERE food_id=? AND name=?", (food_id, unit)).fetchone()
    if not u:
        raise ApiError(400, "Unknown unit for this food")
    grams = u["grams"] * qty
    factor = grams / 100.0
    out = {"food_id": food_id, "name": food["name"], "unit": unit, "qty": qty, "grams": round(grams, 2)}
    for k in NUTRIENTS:
        out[k] = (food[k] or 0) * factor
    return out


# ---------------------------------------------------------------- goals

def latest_weight(db, uid, on_or_before=None):
    if on_or_before:
        r = db.execute("SELECT kg FROM weights WHERE user_id=? AND date<=? ORDER BY date DESC LIMIT 1",
                       (uid, on_or_before)).fetchone()
        if r:
            return r["kg"]
    r = db.execute("SELECT kg FROM weights WHERE user_id=? ORDER BY date DESC LIMIT 1", (uid,)).fetchone()
    return r["kg"] if r else None


def profile_row(db, uid):
    row = db.execute("SELECT * FROM profiles WHERE user_id=?", (uid,)).fetchone()
    if not row:
        db.execute("INSERT INTO profiles (user_id) VALUES (?)", (uid,))
        row = db.execute("SELECT * FROM profiles WHERE user_id=?", (uid,)).fetchone()
    return row


def compute_targets(p, weight):
    bmr = tdee = suggested = None
    if p["sex"] in ("male", "female") and p["age"] and p["height_cm"] and weight:
        bmr = 10 * weight + 6.25 * p["height_cm"] - 5 * p["age"] + (5 if p["sex"] == "male" else -161)
        tdee = bmr * ACTIVITY.get(p["activity"] or "light", 1.375)
        floor = 1500 if p["sex"] == "male" else 1200
        suggested = max(floor, tdee + (p["rate_kg_week"] or 0) * 7700 / 7)
        suggested = int(round(suggested / 10) * 10)
    kcal = p["calorie_goal"] or suggested or 2000
    pp, cp, fp = p["protein_pct"] or 25, p["carbs_pct"] or 45, p["fat_pct"] or 30
    return {
        "kcal": kcal,
        "protein": round(kcal * pp / 400),
        "carbs": round(kcal * cp / 400),
        "fat": round(kcal * fp / 900),
        "fiber": round(kcal / 1000 * 14),
        "sugar": 50,
        "sodium": 2300,
        "water": p["water_goal"] or 8,
        "bmr": round(bmr) if bmr else None,
        "tdee": round(tdee) if tdee else None,
        "suggested": suggested,
        "manual": bool(p["calorie_goal"]),
    }


def targets_for(db, uid):
    return compute_targets(profile_row(db, uid), latest_weight(db, uid))


# ---------------------------------------------------------------- API routes

ROUTES = []


def route(method, pattern, auth=True):
    def deco(fn):
        ROUTES.append((method, re.compile("^" + pattern + "$"), fn, auth))
        return fn
    return deco


class Ctx:
    def __init__(self, handler, db, uid, body, query):
        self.handler, self.db, self.uid, self.body, self.query = handler, db, uid, body, query

    def q(self, key, default=None):
        return self.query.get(key, [default])[0]


@route("GET", "/api/me", auth=False)
def api_me(c):
    no_users = c.db.execute("SELECT COUNT(*) FROM users").fetchone()[0] == 0
    info = {"signup_allowed": ALLOW_SIGNUP or no_users, "first_user": no_users, "user": None}
    if c.uid:
        info["user"] = c.db.execute("SELECT username FROM users WHERE id=?", (c.uid,)).fetchone()["username"]
    return info


@route("POST", "/api/signup", auth=False)
def api_signup(c):
    if not (ALLOW_SIGNUP or c.db.execute("SELECT COUNT(*) FROM users").fetchone()[0] == 0):
        raise ApiError(403, "Sign-up is closed. Ask the admin to create an account.")
    uid = create_user(c.db, c.body.get("username"), c.body.get("password"))
    return session_reply(c, uid)


def session_reply(c, uid):
    """Start a session. The Android app asks for the token in the body instead of a cookie."""
    token = c.handler.start_session(c.db, uid)
    if c.body.get("want_token"):
        c.handler.pending_cookie = None
        return {"ok": True, "token": token}
    return {"ok": True}


@route("POST", "/api/login", auth=False)
def api_login(c):
    key = c.handler.client_ip()
    if LIMITER.blocked(key):
        raise ApiError(429, "Too many failed attempts. Try again in 15 minutes.")
    row = c.db.execute("SELECT id, pw_hash FROM users WHERE username=?",
                       (str(c.body.get("username") or "").strip(),)).fetchone()
    if not row or not check_password(str(c.body.get("password") or ""), row["pw_hash"]):
        LIMITER.fail(key)
        raise ApiError(401, "Wrong username or password")
    LIMITER.reset(key)
    return session_reply(c, row["id"])


@route("POST", "/api/logout", auth=False)
def api_logout(c):
    token = c.handler.session_token()
    if token:
        c.db.execute("DELETE FROM sessions WHERE token_hash=?", (token_hash(token),))
    c.handler.pending_cookie = f"{COOKIE_NAME}=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
    return {"ok": True}


@route("POST", "/api/password")
def api_password(c):
    row = c.db.execute("SELECT pw_hash FROM users WHERE id=?", (c.uid,)).fetchone()
    if not check_password(str(c.body.get("current") or ""), row["pw_hash"]):
        raise ApiError(400, "Current password is wrong")
    new = str(c.body.get("new") or "")
    if len(new) < 6:
        raise ApiError(400, "New password must be at least 6 characters")
    c.db.execute("UPDATE users SET pw_hash=? WHERE id=?", (hash_password(new), c.uid))
    keep = token_hash(c.handler.session_token() or "")
    c.db.execute("DELETE FROM sessions WHERE user_id=? AND token_hash<>?", (c.uid, keep))
    return {"ok": True}


# --- foods

FOOD_LIST_SQL = """
SELECT f.id, f.name, f.kcal, f.protein, f.carbs, f.fat, f.source, f.user_id,
       (SELECT name || '|' || grams FROM units WHERE food_id=f.id ORDER BY name='گرم', ord LIMIT 1) AS unit1,
       (SELECT 1 FROM favorites WHERE user_id=:uid AND food_id=f.id) AS fav
"""


def food_brief(r):
    out = {"id": r["id"], "name": r["name"], "kcal": rnd(r["kcal"], 0), "protein": rnd(r["protein"]),
           "carbs": rnd(r["carbs"]), "fat": rnd(r["fat"]), "custom": r["user_id"] is not None,
           "source": r["source"] or "", "fav": bool(r["fav"])}
    if r["unit1"]:
        name, grams = r["unit1"].rsplit("|", 1)
        out["unit1"] = {"name": name, "grams": float(grams)}
    return out


@route("GET", "/api/foods/search")
def api_search(c):
    nq = normalize(c.q("q", ""))[:80]
    words = nq.split()[:6]
    if not words:
        return {"results": []}
    params = {"uid": c.uid, "exact": nq, "prefix": nq + "%", "compact": "%" + nq.replace(" ", "") + "%",
              "word1": "% " + words[0] + "%", "limit": 40}
    conds = []
    for i, w in enumerate(words):
        params[f"w{i}"] = f"%{w}%"
        conds.append(f"(f.norm LIKE :w{i} OR f.compact LIKE :w{i})")
    sql = FOOD_LIST_SQL + f"""
    FROM foods f
    LEFT JOIN (SELECT food_id, COUNT(*) AS c FROM entries WHERE user_id=:uid AND food_id IS NOT NULL
               GROUP BY food_id) u ON u.food_id = f.id
    WHERE (f.user_id IS NULL OR f.user_id=:uid) AND (({' AND '.join(conds)}) OR f.compact LIKE :compact)
    ORDER BY (f.norm = :exact) DESC, fav DESC, COALESCE(u.c, 0) DESC, (f.norm LIKE :prefix) DESC,
             (' ' || f.norm LIKE :word1) DESC, (f.user_id IS NOT NULL) DESC, f.rank, length(f.norm)
    LIMIT :limit"""
    return {"results": [food_brief(r) for r in c.db.execute(sql, params)]}


@route("GET", "/api/foods/quick")
def api_quick(c):
    """Recent foods (with last-used portion), favorites and the user's custom foods."""
    recent = []
    for r in c.db.execute("""
        SELECT e.food_id, e.unit, e.qty, e.kcal, MAX(e.id) AS last_id, COUNT(*) AS uses
        FROM entries e WHERE e.user_id=? AND e.food_id IS NOT NULL
        GROUP BY e.food_id ORDER BY last_id DESC LIMIT 25""", (c.uid,)).fetchall():
        last = c.db.execute("SELECT unit, qty, kcal FROM entries WHERE id=?", (r["last_id"],)).fetchone()
        f = c.db.execute(FOOD_LIST_SQL + " FROM foods f WHERE f.id=:fid AND (f.user_id IS NULL OR f.user_id=:uid)",
                         {"uid": c.uid, "fid": r["food_id"]}).fetchone()
        if f:
            item = food_brief(f)
            item["last"] = {"unit": last["unit"], "qty": last["qty"], "kcal": rnd(last["kcal"], 0)}
            item["uses"] = r["uses"]
            recent.append(item)
    favs = [food_brief(r) for r in c.db.execute(
        FOOD_LIST_SQL + " FROM favorites v JOIN foods f ON f.id=v.food_id WHERE v.user_id=:uid "
        "AND (f.user_id IS NULL OR f.user_id=:uid) ORDER BY f.name", {"uid": c.uid})]
    mine = [food_brief(r) for r in c.db.execute(
        FOOD_LIST_SQL + " FROM foods f WHERE f.user_id=:uid ORDER BY f.name", {"uid": c.uid})]
    return {"recent": recent, "favorites": favs, "mine": mine}


@route("GET", r"/api/foods/(\d+)")
def api_food(c, food_id):
    f = c.db.execute(FOOD_LIST_SQL + ", f.fiber, f.sugar, f.sodium FROM foods f "
                     "WHERE f.id=:fid AND (f.user_id IS NULL OR f.user_id=:uid)",
                     {"uid": c.uid, "fid": int(food_id)}).fetchone()
    if not f:
        raise ApiError(404, "Food not found")
    out = food_brief(f)
    out.update({"fiber": rnd(f["fiber"]), "sugar": rnd(f["sugar"]), "sodium": rnd(f["sodium"], 0),
                "units": food_units(c.db, f["id"])})
    last = c.db.execute("SELECT unit, qty FROM entries WHERE user_id=? AND food_id=? ORDER BY id DESC LIMIT 1",
                        (c.uid, f["id"])).fetchone()
    out["last"] = dict(last) if last else None
    return out


def _custom_food_values(b):
    name = text(b.get("name"), "Name", 100)
    serving_name = text(b.get("serving_name") or "serving", "Serving name", 40)
    serving_g = number(b.get("serving_grams"), "Serving size (g)", 0.1, 5000)
    per = 100.0 / serving_g  # convert per-serving values to per-100 g
    vals = {k: number(b.get(k) or 0, k, 0, 100000) * per for k in NUTRIENTS}
    if vals["kcal"] <= 0 and not any(vals[k] for k in ("protein", "carbs", "fat")):
        raise ApiError(400, "Enter calories or macros")
    return name, serving_name, serving_g, vals


def _save_custom_units(db, food_id, serving_name, serving_g):
    db.execute("DELETE FROM units WHERE food_id=?", (food_id,))
    db.execute("INSERT INTO units (food_id, name, grams, ord) VALUES (?,?,?,0)", (food_id, serving_name, serving_g))
    if serving_name != GRAM_UNIT:
        db.execute("INSERT INTO units (food_id, name, grams, ord) VALUES (?,?,1,1)", (food_id, GRAM_UNIT))


@route("POST", "/api/foods")
def api_food_create(c):
    name, serving_name, serving_g, v = _custom_food_values(c.body)
    max_id = c.db.execute("SELECT MAX(id) FROM foods").fetchone()[0] or 0
    fid = max(max_id, CUSTOM_ID_BASE) + 1
    norm = normalize(name)
    c.db.execute("INSERT INTO foods (id, user_id, name, norm, compact, source, rank, kcal, protein, carbs, fat, "
                 "fiber, sugar, sodium) VALUES (?,?,?,?,?,'',0,?,?,?,?,?,?,?)",
                 (fid, c.uid, name, norm, norm.replace(" ", ""), *[v[k] for k in NUTRIENTS]))
    _save_custom_units(c.db, fid, serving_name, serving_g)
    return {"id": fid}


@route("PUT", r"/api/foods/(\d+)")
def api_food_update(c, food_id):
    fid = int(food_id)
    if not c.db.execute("SELECT 1 FROM foods WHERE id=? AND user_id=?", (fid, c.uid)).fetchone():
        raise ApiError(404, "Custom food not found")
    name, serving_name, serving_g, v = _custom_food_values(c.body)
    norm = normalize(name)
    c.db.execute("UPDATE foods SET name=?, norm=?, compact=?, kcal=?, protein=?, carbs=?, fat=?, fiber=?, "
                 "sugar=?, sodium=? WHERE id=?",
                 (name, norm, norm.replace(" ", ""), *[v[k] for k in NUTRIENTS], fid))
    _save_custom_units(c.db, fid, serving_name, serving_g)
    return {"id": fid}


@route("DELETE", r"/api/foods/(\d+)")
def api_food_delete(c, food_id):
    fid = int(food_id)
    cur = c.db.execute("DELETE FROM foods WHERE id=? AND user_id=?", (fid, c.uid))
    if not cur.rowcount:
        raise ApiError(404, "Custom food not found")
    c.db.execute("DELETE FROM favorites WHERE food_id=?", (fid,))
    return {"ok": True}  # past log entries keep their own copy of the numbers


@route("POST", r"/api/favorites/(\d+)")
def api_fav_add(c, food_id):
    if not get_food(c.db, c.uid, int(food_id)):
        raise ApiError(404, "Food not found")
    c.db.execute("INSERT OR IGNORE INTO favorites (user_id, food_id) VALUES (?,?)", (c.uid, int(food_id)))
    return {"fav": True}


@route("DELETE", r"/api/favorites/(\d+)")
def api_fav_del(c, food_id):
    c.db.execute("DELETE FROM favorites WHERE user_id=? AND food_id=?", (c.uid, int(food_id)))
    return {"fav": False}


# --- daily log

def day_payload(db, uid, date):
    d = date.isoformat()
    entries = [entry_json(r) for r in db.execute(
        "SELECT * FROM entries WHERE user_id=? AND date=? ORDER BY id", (uid, d))]
    totals = {k: 0.0 for k in NUTRIENTS}
    for e in entries:
        for k in NUTRIENTS:
            totals[k] += e[k]
    day = db.execute("SELECT water, exercise_kcal FROM days WHERE user_id=? AND date=?", (uid, d)).fetchone()
    w = db.execute("SELECT kg FROM weights WHERE user_id=? AND date=?", (uid, d)).fetchone()
    workouts = [workout_json(r) for r in db.execute(
        "SELECT * FROM workouts WHERE user_id=? AND date=? ORDER BY id", (uid, d))]
    burned = sum(x["kcal"] for x in workouts) + (day["exercise_kcal"] if day else 0)
    pct = exercise_pct(profile_row(db, uid))
    return {
        "date": d,
        "entries": entries,
        "totals": {k: rnd(v, 0 if k in ("kcal", "sodium") else 1) for k, v in totals.items()},
        "targets": targets_for(db, uid),
        "water": day["water"] if day else 0,
        "exercise": counted_exercise(burned, pct),  # what workouts add to today's goal
        "burned": burned,
        "exercise_pct": pct,
        "workouts": workouts,
        "weight": w["kg"] if w else None,
        "last_weight": latest_weight(db, uid, d),
    }


@route("GET", "/api/day")
def api_day(c):
    return day_payload(c.db, c.uid, parse_date(c.q("date")))


def _insert_entry(db, uid, date, meal, e):
    cur = db.execute(
        "INSERT INTO entries (user_id, date, meal, food_id, name, unit, qty, grams, kcal, protein, carbs, fat, "
        "fiber, sugar, sodium, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (uid, date, meal, e.get("food_id"), e["name"], e.get("unit"), e.get("qty"), e.get("grams"),
         *[e.get(k, 0) for k in NUTRIENTS], now_iso()))
    return cur.lastrowid


def _quick_values(b):
    e = {"name": text(b.get("name") or "Quick add", "Name", 100), "food_id": None,
         "unit": None, "qty": None, "grams": None}
    for k in NUTRIENTS:
        e[k] = number(b.get(k) or 0, k, 0, 100000)
    if e["kcal"] <= 0 and not any(e[k] for k in ("protein", "carbs", "fat")):
        raise ApiError(400, "Enter calories")
    if not e["kcal"]:
        e["kcal"] = e["protein"] * 4 + e["carbs"] * 4 + e["fat"] * 9
    return e


@route("POST", "/api/entries")
def api_entry_add(c):
    b = c.body
    date = parse_date(b.get("date")).isoformat()
    meal = meal_of(b.get("meal"))
    if b.get("food_id") is not None:
        qty = number(b.get("qty"), "Amount", 0.001, 100000)
        e = compute_portion(c.db, c.uid, int(b["food_id"]), str(b.get("unit") or ""), qty)
        if not e:
            raise ApiError(404, "Food not found")
    else:
        e = _quick_values(b)
    return {"id": _insert_entry(c.db, c.uid, date, meal, e)}


@route("PATCH", r"/api/entries/(\d+)")
def api_entry_edit(c, entry_id):
    r = c.db.execute("SELECT * FROM entries WHERE id=? AND user_id=?", (int(entry_id), c.uid)).fetchone()
    if not r:
        raise ApiError(404, "Entry not found")
    b = c.body
    meal = meal_of(b["meal"]) if "meal" in b else r["meal"]
    date = parse_date(b["date"]).isoformat() if "date" in b else r["date"]
    if r["food_id"] is not None:
        qty = number(b.get("qty", r["qty"]), "Amount", 0.001, 100000)
        unit = str(b.get("unit", r["unit"]))
        e = compute_portion(c.db, c.uid, r["food_id"], unit, qty)
        if e is None:  # food was deleted: scale the stored numbers instead
            if unit != r["unit"] or not r["qty"]:
                raise ApiError(400, "This food was deleted; only the amount can be changed")
            f = qty / r["qty"]
            e = {k: r[k] * f for k in NUTRIENTS}
            e.update(qty=qty, unit=unit, grams=(r["grams"] or 0) * f, name=r["name"])
    else:
        e = _quick_values({**{k: r[k] for k in ("name",) + NUTRIENTS}, **b})
        e.update(qty=None, unit=None, grams=None)
    c.db.execute("UPDATE entries SET date=?, meal=?, name=?, unit=?, qty=?, grams=?, kcal=?, protein=?, carbs=?, "
                 "fat=?, fiber=?, sugar=?, sodium=? WHERE id=?",
                 (date, meal, e["name"], e["unit"], e["qty"], e["grams"], *[e[k] for k in NUTRIENTS], r["id"]))
    return {"ok": True}


@route("DELETE", r"/api/entries/(\d+)")
def api_entry_delete(c, entry_id):
    c.db.execute("DELETE FROM entries WHERE id=? AND user_id=?", (int(entry_id), c.uid))
    return {"ok": True}


@route("POST", "/api/entries/copy")
def api_entry_copy(c):
    src = parse_date(c.body.get("from"), "from").isoformat()
    dst = parse_date(c.body.get("to"), "to").isoformat()
    meal = c.body.get("meal")
    sql = ("INSERT INTO entries (user_id, date, meal, food_id, name, unit, qty, grams, kcal, protein, carbs, fat, "
           "fiber, sugar, sodium, created_at) SELECT user_id, ?, meal, food_id, name, unit, qty, grams, kcal, "
           "protein, carbs, fat, fiber, sugar, sodium, ? FROM entries WHERE user_id=? AND date=?")
    params = [dst, now_iso(), c.uid, src]
    if meal:
        sql += " AND meal=?"
        params.append(meal_of(meal))
    cur = c.db.execute(sql + " ORDER BY id", params)
    return {"copied": cur.rowcount}


@route("POST", "/api/days")
def api_day_update(c):
    d = parse_date(c.body.get("date")).isoformat()
    c.db.execute("INSERT OR IGNORE INTO days (user_id, date) VALUES (?,?)", (c.uid, d))
    if "water" in c.body:
        c.db.execute("UPDATE days SET water=? WHERE user_id=? AND date=?",
                     (int(number(c.body["water"], "Water", 0, 50)), c.uid, d))
    if "exercise" in c.body:
        c.db.execute("UPDATE days SET exercise_kcal=? WHERE user_id=? AND date=?",
                     (int(number(c.body["exercise"] or 0, "Exercise", 0, 10000)), c.uid, d))
    return {"ok": True}


# --- weight

@route("POST", "/api/weights")
def api_weight_set(c):
    d = parse_date(c.body.get("date")).isoformat()
    kg = number(c.body.get("kg"), "Weight", 20, 400)
    c.db.execute("INSERT OR REPLACE INTO weights (user_id, date, kg) VALUES (?,?,?)", (c.uid, d, round(kg, 2)))
    return {"ok": True}


@route("DELETE", r"/api/weights/(\d{4}-\d{2}-\d{2})")
def api_weight_del(c, date):
    c.db.execute("DELETE FROM weights WHERE user_id=? AND date=?", (c.uid, date))
    return {"ok": True}


# --- profile

def profile_payload(db, uid):
    p = profile_row(db, uid)
    out = {k: p[k] for k in p.keys() if k != "user_id"}
    out["weight"] = latest_weight(db, uid)
    out["targets"] = compute_targets(p, out["weight"])
    return out


@route("GET", "/api/profile")
def api_profile(c):
    return profile_payload(c.db, c.uid)


@route("PUT", "/api/profile")
def api_profile_save(c):
    b = c.body
    sex = b.get("sex") or None
    if sex not in (None, "male", "female"):
        raise ApiError(400, "Invalid sex")
    activity = b.get("activity") or "light"
    if activity not in ACTIVITY:
        raise ApiError(400, "Invalid activity level")
    pcts = [int(number(b.get(k, d), k, 5, 80)) for k, d in
            (("protein_pct", 25), ("carbs_pct", 45), ("fat_pct", 30))]
    if sum(pcts) != 100:
        raise ApiError(400, f"Protein + carbs + fat must add up to 100% (now {sum(pcts)}%)")
    age = number(b.get("age"), "Age", 10, 110, allow_none=True)
    goal = number(b.get("calorie_goal"), "Calorie goal", 800, 10000, allow_none=True)
    old = profile_row(c.db, c.uid)  # also makes sure the row exists
    # Older app versions don't send these two, so keep what is saved.
    pct = int(number(b.get("exercise_pct", exercise_pct(old)), "Workout calories", 0, 100))
    if pct not in (0, 50, 100):
        raise ApiError(400, "Workout calories must be 0, 50 or 100%")
    wgoal = int(number(b.get("workout_goal", old["workout_goal"] or 4), "Training days per week", 1, 7))
    c.db.execute("UPDATE profiles SET exercise_pct=?, workout_goal=? WHERE user_id=?", (pct, wgoal, c.uid))
    c.db.execute(
        "UPDATE profiles SET sex=?, age=?, height_cm=?, activity=?, rate_kg_week=?, target_weight=?, "
        "calorie_goal=?, protein_pct=?, carbs_pct=?, fat_pct=?, water_goal=? WHERE user_id=?",
        (sex, int(age) if age else None, number(b.get("height_cm"), "Height", 100, 250, allow_none=True), activity,
         number(b.get("rate_kg_week") or 0, "Weekly goal", -1.5, 1),
         number(b.get("target_weight"), "Goal weight", 20, 400, allow_none=True),
         int(goal) if goal else None, *pcts, int(number(b.get("water_goal") or 8, "Water goal", 1, 30)),
         c.uid))
    if b.get("weight") not in (None, "") and b.get("date"):
        kg = round(number(b.get("weight"), "Weight", 20, 400), 2)
        if kg != latest_weight(c.db, c.uid):
            c.db.execute("INSERT OR REPLACE INTO weights (user_id, date, kg) VALUES (?,?,?)",
                         (c.uid, parse_date(b["date"]).isoformat(), kg))
    return profile_payload(c.db, c.uid)


# --- workouts

def exercise_pct(p):
    return 100 if p["exercise_pct"] is None else p["exercise_pct"]


def counted_exercise(burned, pct):
    return int(round((burned or 0) * pct / 100))


def workout_kcal(activity, intensity, minutes, kg):
    met = ACTIVITY_BY_KEY.get(activity, ACTIVITY_BY_KEY["other"])[3][INTENSITY_INDEX.get(intensity, 1)]
    return int(round((met - 1) * (kg or DEFAULT_WEIGHT) * (minutes or 0) / 60))


def workout_json(r):
    a = ACTIVITY_BY_KEY.get(r["activity"])
    return {
        "id": r["id"], "date": r["date"], "activity": r["activity"],
        "name": r["name"] if (r["activity"] == "other" or not a) and r["name"] else (a[1] if a else "Workout"),
        "kind": a[2] if a else "other",
        "minutes": r["minutes"], "intensity": r["intensity"],
        "muscles": [m for m in r["muscles"].split(",") if m],
        "distance_km": r["distance_km"], "kcal": r["kcal"], "kcal_manual": bool(r["kcal_manual"]),
        "notes": r["notes"],
    }


def _workout_values(db, uid, b, old=None):
    """Validate a workout. `old` is the saved row when editing; fields missing from `b` keep its values."""
    def get(key, default=None):
        if key in b:
            return b[key]
        if old is not None:
            if key == "muscles":
                return [m for m in old["muscles"].split(",") if m]
            return old[key]
        return default

    date = parse_date(get("date")).isoformat()
    activity = get("activity")
    a = ACTIVITY_BY_KEY.get(activity)
    if not a:
        raise ApiError(400, "Pick an activity")
    name = None
    if activity == "other":
        name = text(get("name"), "Activity name", 60)
    minutes = get("minutes")
    if minutes is None and old is not None and "minutes" not in b:
        pass  # very old calorie-only entries have no duration
    else:
        minutes = int(round(number(minutes, "Duration", 1, 1440)))
    intensity = get("intensity", "moderate")
    if intensity not in INTENSITY_INDEX:
        raise ApiError(400, "Invalid intensity")
    muscles = get("muscles") or []
    if not isinstance(muscles, list) or any(m not in MUSCLE_KEYS for m in muscles):
        raise ApiError(400, "Invalid muscle group")
    muscles = [m for m in MUSCLE_KEYS if m in muscles] if a[2] == "strength" else []
    distance = number(get("distance_km"), "Distance", 0, 1000, allow_none=True) if a[4] else None
    notes = text(get("notes"), "Notes", 300, required=False)
    # Calories: typed by the user, or estimated from the activity, effort, time and body weight.
    if "kcal" in b:
        manual = b["kcal"] not in (None, "")
    else:
        manual = bool(old["kcal_manual"]) if old is not None else False
    if manual:
        kcal = int(round(number(b["kcal"] if "kcal" in b else old["kcal"], "Calories", 0, 10000)))
    else:
        if minutes is None:
            raise ApiError(400, "Duration is required")
        kcal = workout_kcal(activity, intensity, minutes, latest_weight(db, uid, date))
    return {"date": date, "activity": activity, "name": name, "minutes": minutes, "intensity": intensity,
            "muscles": ",".join(muscles), "distance_km": round(distance, 2) if distance else None,
            "kcal": kcal, "kcal_manual": int(manual), "notes": notes}


WORKOUT_COLS = ("date", "activity", "name", "minutes", "intensity", "muscles", "distance_km", "kcal",
                "kcal_manual", "notes")


@route("GET", "/api/workouts/meta")
def api_workout_meta(c):
    """Everything the workout form needs: the activity list, muscles and recent workouts to repeat."""
    recent = []
    for r in c.db.execute(
            "SELECT * FROM workouts WHERE id IN (SELECT MAX(id) FROM workouts WHERE user_id=? AND minutes IS NOT NULL "
            "GROUP BY activity, IFNULL(name, ''), minutes, intensity, muscles) ORDER BY date DESC, id DESC LIMIT 6",
            (c.uid,)):
        recent.append(workout_json(r))
    return {
        "activities": [{"key": k, "name": n, "kind": kind, "met": list(m), "distance": d}
                       for k, n, kind, m, d in ACTIVITIES],
        "intensities": [{"key": k, "name": n} for k, n in INTENSITIES],
        "muscles": [{"key": k, "name": n} for k, n in MUSCLES],
        "weight": latest_weight(c.db, c.uid),
        "default_weight": DEFAULT_WEIGHT,
        "recent": recent,
    }


@route("POST", "/api/workouts")
def api_workout_add(c):
    v = _workout_values(c.db, c.uid, c.body)
    cur = c.db.execute(f"INSERT INTO workouts ({', '.join(WORKOUT_COLS)}, user_id, created_at) "
                       f"VALUES ({', '.join('?' * (len(WORKOUT_COLS) + 2))})",
                       (*[v[k] for k in WORKOUT_COLS], c.uid, now_iso()))
    return {"id": cur.lastrowid, "kcal": v["kcal"]}


@route("PATCH", r"/api/workouts/(\d+)")
def api_workout_edit(c, workout_id):
    old = c.db.execute("SELECT * FROM workouts WHERE id=? AND user_id=?", (int(workout_id), c.uid)).fetchone()
    if not old:
        raise ApiError(404, "Workout not found")
    v = _workout_values(c.db, c.uid, c.body, old)
    c.db.execute(f"UPDATE workouts SET {', '.join(k + '=?' for k in WORKOUT_COLS)} WHERE id=?",
                 (*[v[k] for k in WORKOUT_COLS], old["id"]))
    return {"id": old["id"], "kcal": v["kcal"]}


@route("DELETE", r"/api/workouts/(\d+)")
def api_workout_delete(c, workout_id):
    c.db.execute("DELETE FROM workouts WHERE id=? AND user_id=?", (int(workout_id), c.uid))
    return {"ok": True}


@route("GET", "/api/workouts/stats")
def api_workout_stats(c):
    end = parse_date(c.q("end"), "end")
    days = int(number(c.q("days", "30"), "days", 1, 366))
    start = end - dt.timedelta(days=days - 1)
    s, e = start.isoformat(), end.isoformat()
    p = profile_row(c.db, c.uid)
    goal = p["workout_goal"] or 4
    rows = [workout_json(r) for r in c.db.execute(
        "SELECT * FROM workouts WHERE user_id=? AND date BETWEEN ? AND ? ORDER BY date DESC, id DESC", (c.uid, s, e))]

    # Minutes per day (short ranges) or per 7-day block ending on `end` (long ranges), split by kind.
    size = 1 if days <= 31 else 7
    buckets = []
    b_end = end
    while b_end >= start:
        b_start = max(start, b_end - dt.timedelta(days=size - 1))
        buckets.append({"start": b_start.isoformat(), "end": b_end.isoformat(),
                        "strength": 0, "cardio": 0, "other": 0, "sessions": 0, "kcal": 0})
        b_end = b_start - dt.timedelta(days=1)
    buckets.reverse()
    by_activity = {}
    totals = {"sessions": len(rows), "minutes": 0, "kcal": 0, "strength": 0, "cardio": 0, "other": 0,
              "days": len({x["date"] for x in rows})}
    for x in rows:
        m = x["minutes"] or 0
        totals["minutes"] += m
        totals["kcal"] += x["kcal"]
        totals[x["kind"]] += m
        idx = (dt.date.fromisoformat(x["date"]) - start).days
        if size == 7:  # blocks are aligned to `end`, so the first one may be shorter
            idx = len(buckets) - 1 - (end - dt.date.fromisoformat(x["date"])).days // 7
        bk = buckets[idx]
        bk[x["kind"]] += m
        bk["sessions"] += 1
        bk["kcal"] += x["kcal"]
        a = by_activity.setdefault((x["activity"], x["name"]), {
            "activity": x["activity"], "name": x["name"], "kind": x["kind"], "sessions": 0, "minutes": 0, "kcal": 0})
        a["sessions"] += 1
        a["minutes"] += m
        a["kcal"] += x["kcal"]

    # Training days in each 7-day block back from `end`, for this week's progress and the goal streak.
    since = (end - dt.timedelta(days=7 * 104)).isoformat()
    trained = {dt.date.fromisoformat(r[0]) for r in c.db.execute(
        "SELECT DISTINCT date FROM workouts WHERE user_id=? AND date BETWEEN ? AND ?", (c.uid, since, e))}

    def block_days(k):
        hi = end - dt.timedelta(days=7 * k)
        return sum(1 for d in trained if hi - dt.timedelta(days=6) <= d <= hi)

    week_rows = [x for x in rows if x["date"] >= (end - dt.timedelta(days=6)).isoformat()]
    if days < 7:
        week_rows = [workout_json(r) for r in c.db.execute(
            "SELECT * FROM workouts WHERE user_id=? AND date BETWEEN ? AND ?",
            (c.uid, (end - dt.timedelta(days=6)).isoformat(), e))]
    week = {"days": block_days(0), "sessions": len(week_rows), "goal": goal,
            "minutes": sum(x["minutes"] or 0 for x in week_rows), "kcal": sum(x["kcal"] for x in week_rows)}
    streak = 0
    k = 0 if week["days"] >= goal else 1  # the current week may still be in progress
    while k < 104 and block_days(k) >= goal:
        streak += 1
        k += 1

    # When each muscle group was last trained (a full-body session counts for all of them).
    last = {}
    counts = {m: 0 for m in MUSCLE_KEYS}
    for r in c.db.execute("SELECT date, muscles FROM workouts WHERE user_id=? AND muscles != '' AND date <= ? "
                          "ORDER BY date", (c.uid, e)):
        ms = r["muscles"].split(",")
        hit = MUSCLE_KEYS if "full_body" in ms else ms
        for m in hit:
            if m in counts:
                last[m] = r["date"]
                if r["date"] >= s:
                    counts[m] += 1
    muscles = [{"key": k2, "name": n, "last": last.get(k2),
                "days_ago": (end - dt.date.fromisoformat(last[k2])).days if k2 in last else None,
                "sessions": counts[k2]} for k2, n in MUSCLES if k2 != "full_body"]

    return {
        "start": s, "end": e, "bucket_days": size, "buckets": buckets, "totals": totals,
        "week": week, "goal_streak": streak,
        "by_activity": sorted(by_activity.values(), key=lambda a: -a["minutes"]),
        "muscles": muscles, "workouts": rows[:200],
    }


# --- history & stats

@route("GET", "/api/stats")
def api_stats(c):
    end = parse_date(c.q("end"), "end")
    days = int(number(c.q("days", "30"), "days", 1, 366))
    start = end - dt.timedelta(days=days - 1)
    s, e = start.isoformat(), end.isoformat()
    targets = targets_for(c.db, c.uid)
    by_day = {r["date"]: r for r in c.db.execute(
        "SELECT date, SUM(kcal) kcal, SUM(protein) protein, SUM(carbs) carbs, SUM(fat) fat, COUNT(*) n "
        "FROM entries WHERE user_id=? AND date BETWEEN ? AND ? GROUP BY date", (c.uid, s, e))}
    pct = exercise_pct(profile_row(c.db, c.uid))
    burned = {}
    for r in c.db.execute(
            "SELECT date, SUM(kcal) k FROM (SELECT date, kcal FROM workouts WHERE user_id=:u AND date BETWEEN :s AND :e "
            "UNION ALL SELECT date, exercise_kcal FROM days WHERE user_id=:u AND date BETWEEN :s AND :e) GROUP BY date",
            {"u": c.uid, "s": s, "e": e}):
        burned[r["date"]] = r["k"] or 0
    exercise = {d: counted_exercise(k, pct) for d, k in burned.items()}
    series, logged, on_target = [], [], 0
    for i in range(days):
        d = (start + dt.timedelta(days=i)).isoformat()
        r = by_day.get(d)
        budget = targets["kcal"] + exercise.get(d, 0)
        item = {"date": d, "budget": budget, "exercise": exercise.get(d, 0), "kcal": None}
        if r:
            item.update(kcal=round(r["kcal"]), protein=rnd(r["protein"]), carbs=rnd(r["carbs"]), fat=rnd(r["fat"]))
            logged.append(item)
            if abs(r["kcal"] - budget) <= budget * 0.1:
                on_target += 1
        series.append(item)

    def avg(k):
        return round(sum(x[k] for x in logged) / len(logged), 1) if logged else None

    # Streak: consecutive logged days ending today (or yesterday if today is still empty).
    logged_dates = {r[0] for r in c.db.execute(
        "SELECT DISTINCT date FROM entries WHERE user_id=? AND date<=? AND date>=?",
        (c.uid, e, (end - dt.timedelta(days=730)).isoformat()))}
    streak, cur = 0, end
    if e not in logged_dates:
        cur = end - dt.timedelta(days=1)
    while cur.isoformat() in logged_dates:
        streak += 1
        cur -= dt.timedelta(days=1)

    weights = [dict(r) for r in c.db.execute(
        "SELECT date, kg FROM weights WHERE user_id=? AND date BETWEEN ? AND ? ORDER BY date", (c.uid, s, e))]
    top = [{"name": r["name"], "kcal": round(r["kcal"]), "times": r["n"]} for r in c.db.execute(
        "SELECT name, SUM(kcal) kcal, COUNT(*) n FROM entries WHERE user_id=? AND date BETWEEN ? AND ? "
        "GROUP BY name ORDER BY kcal DESC LIMIT 10", (c.uid, s, e))]
    meals = {r["meal"]: round(r["kcal"]) for r in c.db.execute(
        "SELECT meal, SUM(kcal) kcal FROM entries WHERE user_id=? AND date BETWEEN ? AND ? GROUP BY meal",
        (c.uid, s, e))}
    p = profile_row(c.db, c.uid)
    return {
        "start": s, "end": e, "days": series, "targets": targets,
        "logged_days": len(logged), "on_target": on_target, "streak": streak,
        "avg": {k: avg(k) for k in ("kcal", "protein", "carbs", "fat")},
        "weights": weights, "current_weight": latest_weight(c.db, c.uid),
        "target_weight": p["target_weight"], "rate_kg_week": p["rate_kg_week"],
        "top_foods": top, "meals": meals,
    }


@route("GET", "/api/weights")
def api_weights(c):
    return {"weights": [dict(r) for r in c.db.execute(
        "SELECT date, kg FROM weights WHERE user_id=? ORDER BY date DESC", (c.uid,))]}


@route("GET", "/api/export")
def api_export(c):
    kind = c.q("type", "entries")
    buf = io.StringIO()
    w = csv.writer(buf)
    if kind == "weights":
        w.writerow(["date", "kg"])
        w.writerows(c.db.execute("SELECT date, kg FROM weights WHERE user_id=? ORDER BY date", (c.uid,)))
    elif kind == "workouts":
        w.writerow(["date", "activity", "minutes", "intensity", "muscles", "distance_km", "kcal", "notes"])
        for r in c.db.execute("SELECT * FROM workouts WHERE user_id=? ORDER BY date, id", (c.uid,)):
            x = workout_json(r)
            w.writerow([x["date"], x["name"], x["minutes"], x["intensity"], " ".join(x["muscles"]),
                        x["distance_km"], x["kcal"], x["notes"]])
    else:
        kind = "entries"
        cols = ["date", "meal", "name", "qty", "unit", "grams", *NUTRIENTS]
        w.writerow(cols)
        for r in c.db.execute(f"SELECT {', '.join(cols)} FROM entries WHERE user_id=? ORDER BY date, id", (c.uid,)):
            w.writerow([round(v, 2) if isinstance(v, float) else v for v in r])
    data = ("﻿" + buf.getvalue()).encode("utf-8")  # BOM so Excel shows Persian correctly
    return RawResponse(data, "text/csv; charset=utf-8",
                       {"Content-Disposition": f'attachment; filename="calories-{kind}.csv"'})


class RawResponse:
    def __init__(self, data, ctype, headers=None):
        self.data, self.ctype, self.headers = data, ctype, headers or {}


# ---------------------------------------------------------------- HTTP handler

CSP = ("default-src 'self'; img-src 'self' data:; style-src 'self' https://fonts.googleapis.com; "
       "font-src https://fonts.gstatic.com; script-src 'self'; connect-src 'self'; "
       "frame-ancestors 'none'; base-uri 'none'; form-action 'self'")


class Handler(BaseHTTPRequestHandler):
    server_version = "CalorieTracker"
    sys_version = ""
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write(f"{self.client_ip()} - {fmt % args}\n")

    def client_ip(self):
        if TRUST_PROXY:
            fwd = self.headers.get("X-Forwarded-For", "")
            if fwd:  # the last hop is the one our proxy added; earlier ones can be faked
                return fwd.split(",")[-1].strip()
        return self.client_address[0]

    def bearer_token(self):
        auth = self.headers.get("Authorization", "")
        if auth[:7].lower() == "bearer ":
            return auth[7:].strip() or None
        return None

    def session_token(self):
        bearer = self.bearer_token()
        if bearer:
            return bearer
        raw = self.headers.get("Cookie")
        if not raw:
            return None
        try:
            morsel = SimpleCookie(raw).get(COOKIE_NAME)
        except Exception:
            return None
        return morsel.value if morsel else None

    def current_user(self, db):
        token = self.session_token()
        if not token:
            return None
        row = db.execute("SELECT user_id, expires_at FROM sessions WHERE token_hash=?",
                         (token_hash(token),)).fetchone()
        if not row or row["expires_at"] < time.time():
            return None
        return row["user_id"]

    def is_https(self):
        if COOKIE_SECURE in ("0", "1"):
            return COOKIE_SECURE == "1"
        return self.headers.get("X-Forwarded-Proto", "").lower() == "https"

    def start_session(self, db, uid):
        token = secrets.token_urlsafe(32)
        expires = int(time.time()) + SESSION_DAYS * 86400
        db.execute("DELETE FROM sessions WHERE expires_at < ?", (int(time.time()),))
        db.execute("INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?,?,?)",
                   (token_hash(token), uid, expires))
        secure = "; Secure" if self.is_https() else ""
        self.pending_cookie = (f"{COOKIE_NAME}={token}; Path=/; Max-Age={SESSION_DAYS * 86400}; "
                               f"HttpOnly; SameSite=Lax{secure}")
        return token

    def send(self, status, data, ctype, extra=None):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "same-origin")
        self.send_header("X-Frame-Options", "DENY")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        cookie = getattr(self, "pending_cookie", None)
        if cookie:
            self.send_header("Set-Cookie", cookie)
            self.pending_cookie = None
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def send_json(self, status, obj):
        data = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send(status, data, "application/json; charset=utf-8", {"Cache-Control": "no-store"})

    def do_GET(self):
        self.dispatch("GET")

    def do_HEAD(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")

    def do_PUT(self):
        self.dispatch("PUT")

    def do_PATCH(self):
        self.dispatch("PATCH")

    def do_DELETE(self):
        self.dispatch("DELETE")

    def dispatch(self, method):
        self.pending_cookie = None
        url = urlparse(self.path)
        if not url.path.startswith("/api/"):
            if method != "GET":
                return self.send_json(405, {"error": "Method not allowed"})
            return self.serve_static(url.path)
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length > MAX_BODY:
            self.close_connection = True
            return self.send_json(413, {"error": "Request too large"})
        raw = self.rfile.read(length) if length else b""
        if method != "GET" and not self.bearer_token() and self.headers.get("X-Requested-With") != "fetch":
            # Browsers can't send this header (or an Authorization header) cross-site
            # without a CORS preflight we never approve, so this blocks CSRF.
            return self.send_json(403, {"error": "Missing X-Requested-With header"})
        for m, pattern, fn, auth in ROUTES:
            match = pattern.match(url.path)
            if m == method and match:
                break
        else:
            known = any(p.match(url.path) for _, p, _, _ in ROUTES)
            return self.send_json(405 if known else 404, {"error": "Not found"})
        db = connect()
        try:
            uid = self.current_user(db)
            if auth and not uid:
                return self.send_json(401, {"error": "Please log in"})
            body = {}
            if raw:
                try:
                    body = json.loads(raw)
                except ValueError:
                    return self.send_json(400, {"error": "Invalid JSON"})
                if not isinstance(body, dict):
                    return self.send_json(400, {"error": "Expected a JSON object"})
            result = fn(Ctx(self, db, uid, body, parse_qs(url.query)), *match.groups())
            db.commit()
            if isinstance(result, RawResponse):
                return self.send(200, result.data, result.ctype, {"Cache-Control": "no-store", **result.headers})
            return self.send_json(200, result)
        except ApiError as e:
            db.rollback()
            return self.send_json(e.status, {"error": e.message})
        except Exception as e:  # keep the server alive, log the bug
            db.rollback()
            self.log_error("error on %s %s: %r", method, url.path, e)
            import traceback
            traceback.print_exc()
            return self.send_json(500, {"error": "Server error"})
        finally:
            db.close()

    def serve_static(self, path):
        if path in ("/", "/index.html"):
            rel = "index.html"
        elif path.startswith("/static/"):
            rel = path[len("/static/"):]
        else:
            return self.send(404, b"Not found", "text/plain; charset=utf-8")
        full = os.path.normpath(os.path.join(STATIC_DIR, rel))
        if not full.startswith(STATIC_DIR + os.sep) or not os.path.isfile(full):
            return self.send(404, b"Not found", "text/plain; charset=utf-8")
        with open(full, "rb") as fh:
            data = fh.read()
        ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
        if full.endswith(".webmanifest"):
            ctype = "application/manifest+json"
        if ctype.startswith("text/") or ctype in ("application/javascript", "application/json"):
            ctype += "; charset=utf-8"
        self.send(200, data, ctype, {"Cache-Control": "no-cache", "Content-Security-Policy": CSP})


# ---------------------------------------------------------------- CLI

def cli(argv):
    init_db(verbose=False)
    db = connect()
    try:
        cmd = argv[0]
        if cmd in ("adduser", "passwd") and len(argv) == 2:
            pw = getpass.getpass("Password: ")
            if pw != getpass.getpass("Repeat password: "):
                sys.exit("Passwords do not match")
            if cmd == "adduser":
                create_user(db, argv[1], pw)
                print(f"Created user {argv[1]}")
            else:
                if len(pw) < 6:
                    sys.exit("Password must be at least 6 characters")
                row = db.execute("SELECT id FROM users WHERE username=?", (argv[1],)).fetchone()
                if not row:
                    sys.exit("No such user")
                db.execute("UPDATE users SET pw_hash=? WHERE id=?", (hash_password(pw), row["id"]))
                db.execute("DELETE FROM sessions WHERE user_id=?", (row["id"],))
                print(f"Password changed for {argv[1]}")
            db.commit()
        elif cmd == "backup" and len(argv) == 2:
            dest = sqlite3.connect(argv[1])
            with dest:
                db.backup(dest)
            dest.close()
            print(f"Backed up to {argv[1]}")
        elif cmd == "users":
            for r in db.execute("SELECT username, created_at FROM users ORDER BY id"):
                print(r["username"], r["created_at"])
        else:
            sys.exit(__doc__)
    except ApiError as e:
        sys.exit(e.message)
    finally:
        db.close()


def main():
    if len(sys.argv) > 1:
        return cli(sys.argv[1:])
    init_db()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    print(f"Calorie tracker running on http://{HOST}:{PORT}  (data: {db_path()})")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
