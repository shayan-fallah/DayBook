#!/usr/bin/env python3
"""Daybook: food, workouts, time tracking and planning. A small self-hosted web app.

Python 3.9+ standard library only. Data lives in one SQLite file.

    python3 app.py                 # run the server
    python3 app.py adduser NAME    # create an account
    python3 app.py passwd NAME     # reset a password
    python3 app.py users           # list accounts
    python3 app.py backup FILE     # safe copy of the database (works while running)
    python3 app.py import-sakura FILE NAME
                                   # copy projects, tasks and time from Sakura Log (logger.db or backup .json)
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
import traceback
import urllib.error
import urllib.request
from html import escape as html_escape
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, urlencode, urlparse

BASE = os.path.dirname(os.path.abspath(__file__))
HOST = os.environ.get("HOST", "0.0.0.0")
PORT = int(os.environ.get("PORT", "8080"))
DATA_DIR = os.environ.get("DATA_DIR", os.path.join(BASE, "data"))
DATASET_DIR = os.environ.get("DATASET_DIR", BASE)
STATIC_DIR = os.path.join(BASE, "static")
ALLOW_SIGNUP = os.environ.get("ALLOW_SIGNUP", "0") == "1"
TRUST_PROXY = os.environ.get("TRUST_PROXY", "0") == "1"
COOKIE_SECURE = os.environ.get("COOKIE_SECURE", "auto")  # auto | 1 | 0
PUBLIC_URL = os.environ.get("PUBLIC_URL", "").rstrip("/")  # e.g. https://tracker.example.com

# Google Calendar (optional). Create an OAuth client in Google Cloud, see README.
GOOGLE_CLIENT_ID = os.environ.get("GOOGLE_CLIENT_ID", "")
GOOGLE_CLIENT_SECRET = os.environ.get("GOOGLE_CLIENT_SECRET", "")
GOOGLE_AUTH_URL = os.environ.get("GOOGLE_AUTH_URL", "https://accounts.google.com/o/oauth2/v2/auth")
GOOGLE_TOKEN_URL = os.environ.get("GOOGLE_TOKEN_URL", "https://oauth2.googleapis.com/token")
GOOGLE_REVOKE_URL = os.environ.get("GOOGLE_REVOKE_URL", "https://oauth2.googleapis.com/revoke")
GOOGLE_API = os.environ.get("GOOGLE_API_URL", "https://www.googleapis.com/calendar/v3")
GOOGLE_SCOPES = "https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/calendar.readonly"

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
CREATE TABLE IF NOT EXISTS food_overrides (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, food_id INTEGER NOT NULL,
    name TEXT NOT NULL, norm TEXT NOT NULL, compact TEXT NOT NULL,
    kcal REAL, protein REAL, carbs REAL, fat REAL, fiber REAL, sugar REAL, sodium REAL,
    units TEXT, updated_at TEXT NOT NULL, PRIMARY KEY (user_id, food_id));
CREATE TABLE IF NOT EXISTS projects (
    id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL, color TEXT NOT NULL DEFAULT '#2a78d6', archived INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS projects_user ON projects(user_id);
CREATE TABLE IF NOT EXISTS tasks (
    id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    project_id INTEGER REFERENCES projects(id) ON DELETE SET NULL,
    title TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '', due TEXT, priority INTEGER NOT NULL DEFAULT 0,
    done INTEGER NOT NULL DEFAULT 0, done_at INTEGER, created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS tasks_user ON tasks(user_id, done);
CREATE TABLE IF NOT EXISTS time_entries (
    id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    project_id INTEGER REFERENCES projects(id) ON DELETE SET NULL,
    task_id INTEGER REFERENCES tasks(id) ON DELETE SET NULL,
    description TEXT NOT NULL DEFAULT '', start_ts INTEGER NOT NULL, end_ts INTEGER);
CREATE INDEX IF NOT EXISTS time_user_start ON time_entries(user_id, start_ts);
CREATE TABLE IF NOT EXISTS plan_blocks (
    id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '',
    project_id INTEGER REFERENCES projects(id) ON DELETE SET NULL,
    task_id INTEGER REFERENCES tasks(id) ON DELETE SET NULL,
    start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL, series TEXT,
    gcal_id TEXT, gcal_cal TEXT, gcal_dirty INTEGER NOT NULL DEFAULT 1, gcal_pushed TEXT, gcal_error TEXT,
    updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS plan_user_start ON plan_blocks(user_id, start_ts);
CREATE TABLE IF NOT EXISTS gcal_deletes (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, cal TEXT NOT NULL, event_id TEXT NOT NULL,
    PRIMARY KEY (user_id, cal, event_id));
CREATE TABLE IF NOT EXISTS google_accounts (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    refresh_token TEXT NOT NULL, access_token TEXT, access_expires INTEGER NOT NULL DEFAULT 0,
    email TEXT, calendar_id TEXT NOT NULL DEFAULT 'primary', show_events INTEGER NOT NULL DEFAULT 1,
    connected_at INTEGER NOT NULL, last_sync INTEGER, last_error TEXT, needs_reauth INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS oauth_states (
    state TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    redirect_uri TEXT NOT NULL, expires INTEGER NOT NULL);
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
    for name, ddl in (("exercise_pct", "INTEGER DEFAULT 100"), ("workout_goal", "INTEGER DEFAULT 4"),
                      ("prefs", "TEXT"), ("ics_token", "TEXT")):
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
    """The food as this user sees it: their own edits of a dataset food replace its values. None if not found."""
    row = db.execute("SELECT * FROM foods WHERE id=? AND (user_id IS NULL OR user_id=?)",
                     (food_id, uid)).fetchone()
    if not row:
        return None
    food = dict(row)
    food["edited"] = False
    food["edited_units"] = None
    if row["user_id"] is None:
        o = db.execute("SELECT * FROM food_overrides WHERE user_id=? AND food_id=?", (uid, food_id)).fetchone()
        if o:
            food["original"] = {k: food[k] for k in ("name",) + NUTRIENTS}
            food.update({k: o[k] for k in ("name",) + NUTRIENTS})
            food["edited"] = True
            food["edited_units"] = o["units"]
    return food


def _units_from_json(raw):
    """Units saved with a user's edit: the units they listed, then grams."""
    units = [{"name": n, "grams": g} for n, g in json.loads(raw)]
    return units + [{"name": GRAM_UNIT, "grams": 1}]


def food_units(db, food_id, food=None):
    """Units of a food, grams last. Pass `food` from get_food() to use the user's edited units."""
    if food is not None and food.get("edited_units"):
        return _units_from_json(food["edited_units"])
    return [{"name": r["name"], "grams": r["grams"]}
            for r in db.execute("SELECT name, grams FROM units WHERE food_id=? ORDER BY name='گرم', ord", (food_id,))]


def compute_portion(db, uid, food_id, unit, qty):
    """Nutrition for qty x unit of a food, or None if the food is gone."""
    food = get_food(db, uid, food_id)
    if not food:
        return None
    u = next((x for x in food_units(db, food_id, food) if x["name"] == unit), None)
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
        self.after = []  # callbacks to run once the request's changes are committed

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

# Every food list reads through this: the user's edits of dataset foods (food_overrides) replace its values.
FOOD_SELECT = """
SELECT f.id, COALESCE(o.name, f.name) AS name, COALESCE(o.kcal, f.kcal) AS kcal,
       COALESCE(o.protein, f.protein) AS protein, COALESCE(o.carbs, f.carbs) AS carbs,
       COALESCE(o.fat, f.fat) AS fat, f.source, f.user_id, o.units AS edited_units, (o.food_id IS NOT NULL) AS edited,
       (SELECT name || '|' || grams FROM units WHERE food_id=f.id ORDER BY name='گرم', ord LIMIT 1) AS unit1,
       (SELECT 1 FROM favorites WHERE user_id=:uid AND food_id=f.id) AS fav
FROM foods f LEFT JOIN food_overrides o ON o.user_id=:uid AND o.food_id=f.id
"""
F_NAME, F_NORM, F_COMPACT = "COALESCE(o.name, f.name)", "COALESCE(o.norm, f.norm)", "COALESCE(o.compact, f.compact)"


def food_brief(r):
    out = {"id": r["id"], "name": r["name"], "kcal": rnd(r["kcal"], 0), "protein": rnd(r["protein"]),
           "carbs": rnd(r["carbs"]), "fat": rnd(r["fat"]), "custom": r["user_id"] is not None,
           "edited": bool(r["edited"]), "source": r["source"] or "", "fav": bool(r["fav"])}
    if r["edited_units"]:
        out["unit1"] = _units_from_json(r["edited_units"])[0]
    elif r["unit1"]:
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
        conds.append(f"({F_NORM} LIKE :w{i} OR {F_COMPACT} LIKE :w{i})")
    sql = FOOD_SELECT + f"""
    LEFT JOIN (SELECT food_id, COUNT(*) AS c FROM entries WHERE user_id=:uid AND food_id IS NOT NULL
               GROUP BY food_id) u ON u.food_id = f.id
    WHERE (f.user_id IS NULL OR f.user_id=:uid) AND (({' AND '.join(conds)}) OR {F_COMPACT} LIKE :compact)
    ORDER BY ({F_NORM} = :exact) DESC, fav DESC, COALESCE(u.c, 0) DESC, ({F_NORM} LIKE :prefix) DESC,
             (' ' || {F_NORM} LIKE :word1) DESC, (f.user_id IS NOT NULL) DESC, f.rank, length({F_NORM})
    LIMIT :limit"""
    return {"results": [food_brief(r) for r in c.db.execute(sql, params)]}


@route("GET", "/api/foods/quick")
def api_quick(c):
    """Recent foods (with last-used portion), favorites, the user's custom foods and edited dataset foods."""
    recent = []
    for r in c.db.execute("""
        SELECT e.food_id, MAX(e.id) AS last_id, COUNT(*) AS uses
        FROM entries e WHERE e.user_id=? AND e.food_id IS NOT NULL
        GROUP BY e.food_id ORDER BY last_id DESC LIMIT 25""", (c.uid,)).fetchall():
        last = c.db.execute("SELECT unit, qty, kcal FROM entries WHERE id=?", (r["last_id"],)).fetchone()
        f = c.db.execute(FOOD_SELECT + " WHERE f.id=:fid AND (f.user_id IS NULL OR f.user_id=:uid)",
                         {"uid": c.uid, "fid": r["food_id"]}).fetchone()
        if f:
            item = food_brief(f)
            item["last"] = {"unit": last["unit"], "qty": last["qty"], "kcal": rnd(last["kcal"], 0)}
            item["uses"] = r["uses"]
            recent.append(item)
    favs = [food_brief(r) for r in c.db.execute(
        FOOD_SELECT + f" JOIN favorites v ON v.food_id=f.id AND v.user_id=:uid "
        f"WHERE (f.user_id IS NULL OR f.user_id=:uid) ORDER BY {F_NAME}", {"uid": c.uid})]
    mine = [food_brief(r) for r in c.db.execute(
        FOOD_SELECT + " WHERE f.user_id=:uid ORDER BY f.name", {"uid": c.uid})]
    edited = [food_brief(r) for r in c.db.execute(
        FOOD_SELECT + " WHERE f.user_id IS NULL AND o.food_id IS NOT NULL ORDER BY o.name", {"uid": c.uid})]
    return {"recent": recent, "favorites": favs, "mine": mine, "edited": edited}


@route("GET", r"/api/foods/(\d+)")
def api_food(c, food_id):
    f = get_food(c.db, c.uid, int(food_id))
    if not f:
        raise ApiError(404, "Food not found")
    fav = c.db.execute("SELECT 1 FROM favorites WHERE user_id=? AND food_id=?", (c.uid, f["id"])).fetchone()
    units = food_units(c.db, f["id"], f)
    out = {"id": f["id"], "name": f["name"], "custom": f["user_id"] is not None, "edited": f["edited"],
           "source": f["source"] or "", "fav": bool(fav), "units": units}
    for k in NUTRIENTS:
        out[k] = rnd(f[k], 0 if k in ("kcal", "sodium") else 1)
    out["unit1"] = units[0] if units else None
    if f["edited"]:
        orig = f["original"]
        out["original"] = {"name": orig["name"], **{k: rnd(orig[k], 0 if k in ("kcal", "sodium") else 1)
                                                    for k in NUTRIENTS}}
    last = c.db.execute("SELECT unit, qty FROM entries WHERE user_id=? AND food_id=? ORDER BY id DESC LIMIT 1",
                        (c.uid, f["id"])).fetchone()
    out["last"] = dict(last) if last else None
    out["uses"] = c.db.execute("SELECT COUNT(*) FROM entries WHERE user_id=? AND food_id=?",
                               (c.uid, f["id"])).fetchone()[0]
    return out


def _food_values(b, creating=False):
    """Validate the food editor. Returns (name, nutrients per 100 g, extra units [(name, grams)]).

    The current editor sends `units` (any number of named portions) and the nutrients for `basis_grams` grams.
    Older app versions send one serving (`serving_name`, `serving_grams`) and the nutrients per serving.
    """
    name = text(b.get("name"), "Name", 100)
    if "units" in b:
        basis = number(b.get("basis_grams", 100), "Amount the values are for", 0.1, 5000)
        raw = b.get("units") or []
        if not isinstance(raw, list) or len(raw) > 30:
            raise ApiError(400, "Invalid units")
        units, seen = [], set()
        for u in raw:
            if not isinstance(u, dict):
                raise ApiError(400, "Invalid units")
            un = text(u.get("name"), "Unit name", 40)
            if un in (GRAM_UNIT, "g", "gram", "grams"):
                continue  # grams are always available
            if un in seen:
                raise ApiError(400, f"The unit “{un}” is listed twice")
            seen.add(un)
            units.append((un, round(number(u.get("grams"), f"Weight of “{un}” (g)", 0.01, 5000), 3)))
    else:
        serving_name = text(b.get("serving_name") or "serving", "Serving name", 40)
        basis = number(b.get("serving_grams"), "Serving size (g)", 0.1, 5000)
        units = [] if serving_name == GRAM_UNIT else [(serving_name, basis)]
    per = 100.0 / basis  # convert to per-100 g
    vals = {k: number(b.get(k) or 0, k, 0, 100000) * per for k in NUTRIENTS}
    if creating and vals["kcal"] <= 0 and not any(vals[k] for k in ("protein", "carbs", "fat")):
        raise ApiError(400, "Enter calories or macros")
    return name, vals, units


def _save_custom_units(db, food_id, units):
    db.execute("DELETE FROM units WHERE food_id=?", (food_id,))
    db.executemany("INSERT INTO units (food_id, name, grams, ord) VALUES (?,?,?,?)",
                   [(food_id, n, g, i) for i, (n, g) in enumerate(units)])
    db.execute("INSERT INTO units (food_id, name, grams, ord) VALUES (?,?,1,1000)", (food_id, GRAM_UNIT))


def _refresh_entries(db, uid, food_id):
    """Recalculate this user's log entries of a food from its current values. Returns how many changed."""
    food = get_food(db, uid, food_id)
    if not food:
        return 0
    grams_of = {u["name"]: u["grams"] for u in food_units(db, food_id, food)}
    n = 0
    for e in db.execute("SELECT id, unit, qty, grams FROM entries WHERE user_id=? AND food_id=?",
                        (uid, food_id)).fetchall():
        grams = grams_of[e["unit"]] * e["qty"] if e["unit"] in grams_of and e["qty"] else (e["grams"] or 0)
        f = grams / 100.0
        db.execute("UPDATE entries SET name=?, grams=?, kcal=?, protein=?, carbs=?, fat=?, fiber=?, sugar=?, sodium=? "
                   "WHERE id=?", (food["name"], round(grams, 2), *[(food[k] or 0) * f for k in NUTRIENTS], e["id"]))
        n += 1
    return n


@route("POST", "/api/foods")
def api_food_create(c):
    name, v, units = _food_values(c.body, creating=True)
    max_id = c.db.execute("SELECT MAX(id) FROM foods").fetchone()[0] or 0
    fid = max(max_id, CUSTOM_ID_BASE) + 1
    norm = normalize(name)
    c.db.execute("INSERT INTO foods (id, user_id, name, norm, compact, source, rank, kcal, protein, carbs, fat, "
                 "fiber, sugar, sodium) VALUES (?,?,?,?,?,'',0,?,?,?,?,?,?,?)",
                 (fid, c.uid, name, norm, norm.replace(" ", ""), *[v[k] for k in NUTRIENTS]))
    _save_custom_units(c.db, fid, units)
    return {"id": fid}


@route("PUT", r"/api/foods/(\d+)")
def api_food_update(c, food_id):
    """Edit any food. Your own foods change in place; a dataset food gets a personal copy of its values
    (only you see it, and the original stays available through reset)."""
    fid = int(food_id)
    food = get_food(c.db, c.uid, fid)
    if not food:
        raise ApiError(404, "Food not found")
    name, v, units = _food_values(c.body)
    norm = normalize(name)
    if food["user_id"] is not None:
        c.db.execute("UPDATE foods SET name=?, norm=?, compact=?, kcal=?, protein=?, carbs=?, fat=?, fiber=?, "
                     "sugar=?, sodium=? WHERE id=?",
                     (name, norm, norm.replace(" ", ""), *[v[k] for k in NUTRIENTS], fid))
        _save_custom_units(c.db, fid, units)
    else:
        dataset = [(r["name"], round(r["grams"], 3)) for r in c.db.execute(
            "SELECT name, grams FROM units WHERE food_id=? AND name<>? ORDER BY ord", (fid, GRAM_UNIT))]
        # Keep following the dataset's units unless they were actually changed.
        same = len(units) == len(dataset) and all(
            n1 == n2 and abs(g1 - g2) < 0.01 for (n1, g1), (n2, g2) in zip(units, dataset))
        units_json = None if same else json.dumps(units, ensure_ascii=False)
        c.db.execute("INSERT OR REPLACE INTO food_overrides (user_id, food_id, name, norm, compact, kcal, protein, "
                     "carbs, fat, fiber, sugar, sodium, units, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                     (c.uid, fid, name, norm, norm.replace(" ", ""), *[v[k] for k in NUTRIENTS], units_json,
                      now_iso()))
    updated = _refresh_entries(c.db, c.uid, fid) if c.body.get("update_entries") else 0
    return {"id": fid, "updated_entries": updated}


@route("POST", r"/api/foods/(\d+)/reset")
def api_food_reset(c, food_id):
    """Drop your edits of a dataset food and go back to the original values."""
    fid = int(food_id)
    cur = c.db.execute("DELETE FROM food_overrides WHERE user_id=? AND food_id=?", (c.uid, fid))
    if not cur.rowcount:
        raise ApiError(404, "This food has no edits")
    updated = _refresh_entries(c.db, c.uid, fid) if c.body.get("update_entries") else 0
    return {"id": fid, "updated_entries": updated}


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
    elif kind == "time":
        # Times in the viewer's timezone: `tz` is minutes east of UTC; `from`/`to` limit the range.
        tz = dt.timezone(dt.timedelta(minutes=int(number(c.q("tz", "0"), "tz", -900, 900))))
        frm, to = ts_value(c.q("from"), "from", True) or 0, ts_value(c.q("to"), "to", True) or TS_MAX
        projects = {r[0]: r[1] for r in c.db.execute("SELECT id, name FROM projects WHERE user_id=?", (c.uid,))}
        tasks = {r[0]: r[1] for r in c.db.execute("SELECT id, title FROM tasks WHERE user_id=?", (c.uid,))}
        w.writerow(["date", "start", "end", "hours", "project", "task", "description"])
        t = now_ts()
        for r in c.db.execute("SELECT * FROM time_entries WHERE user_id=? AND start_ts<? AND COALESCE(end_ts, ?)>? "
                              "ORDER BY start_ts", (c.uid, to, t, frm)):
            s = dt.datetime.fromtimestamp(r["start_ts"], tz)
            e = dt.datetime.fromtimestamp(r["end_ts"], tz) if r["end_ts"] else None
            w.writerow([s.date().isoformat(), s.strftime("%H:%M"), e.strftime("%H:%M") if e else "running",
                        round(((r["end_ts"] or t) - r["start_ts"]) / 3600, 2), projects.get(r["project_id"], ""),
                        tasks.get(r["task_id"], ""), r["description"]])
    else:
        kind = "entries"
        cols = ["date", "meal", "name", "qty", "unit", "grams", *NUTRIENTS]
        w.writerow(cols)
        for r in c.db.execute(f"SELECT {', '.join(cols)} FROM entries WHERE user_id=? ORDER BY date, id", (c.uid,)):
            w.writerow([round(v, 2) if isinstance(v, float) else v for v in r])
    data = ("﻿" + buf.getvalue()).encode("utf-8")  # BOM so Excel shows Persian correctly
    return RawResponse(data, "text/csv; charset=utf-8",
                       {"Content-Disposition": f'attachment; filename="daybook-{kind}.csv"'})


class RawResponse:
    def __init__(self, data, ctype, headers=None):
        self.data, self.ctype, self.headers = data, ctype, headers or {}


# ---------------------------------------------------------------- time tracking, to-dos and planning
#
# Times are Unix seconds (UTC). Days, weeks and months depend on the viewer's timezone, so the apps ask for
# ranges in seconds and do the calendar math themselves.

COLOR_RE = re.compile(r"^#[0-9a-fA-F]{6}$")
PROJECT_COLORS = ("#2a78d6", "#eb6834", "#1baf7a", "#8b6cf0", "#e0508f", "#e8a317", "#14a3b8", "#d03b3b")
TS_MIN, TS_MAX = 946684800, 4102444800  # 2000 .. 2100
MAX_ENTRY_SECONDS = 7 * 86400
DEFAULT_PREFS = {"work_goal": 8, "week_start": "auto", "calendar": "gregorian"}
WEEK_STARTS = ("auto", "sat", "sun", "mon")


def now_ts():
    return int(time.time())


def ts_value(value, field, allow_none=False):
    if value is None or value == "":
        if allow_none:
            return None
        raise ApiError(400, f"{field} is required")
    if isinstance(value, bool):
        raise ApiError(400, f"Invalid {field}")
    try:
        v = int(value)
    except (TypeError, ValueError):
        raise ApiError(400, f"Invalid {field}")
    if not TS_MIN <= v <= TS_MAX:
        raise ApiError(400, f"{field} is out of range")
    return v


def ref_id(value, field):
    """An optional id (project, task) from a request."""
    if value is None or value == "":
        return None
    if isinstance(value, bool):
        raise ApiError(400, f"Invalid {field}")
    try:
        return int(value)
    except (TypeError, ValueError):
        raise ApiError(400, f"Invalid {field}")


def owned(db, table, uid, id_, what):
    row = db.execute(f"SELECT * FROM {table} WHERE id=? AND user_id=?", (id_, uid)).fetchone()
    if not row:
        raise ApiError(404, f"{what} not found")
    return row


def long_text(value, field, max_len):
    """Free text that keeps its line breaks, like notes."""
    s = str(value or "").replace("\r\n", "\n").strip()
    if len(s) > max_len:
        raise ApiError(400, f"{field} is too long (at most {max_len} characters)")
    return s


def project_task_refs(db, uid, project_id, task_id):
    """Check that a project and task belong to the user. A task in a project implies that project."""
    if task_id is not None:
        t = owned(db, "tasks", uid, task_id, "Task")
        if t["project_id"] is not None:
            if project_id is not None and project_id != t["project_id"]:
                raise ApiError(400, "That task belongs to another project")
            project_id = t["project_id"]
    if project_id is not None:
        owned(db, "projects", uid, project_id, "Project")
    return project_id, task_id


def sums_by(db, column, uid):
    """Seconds logged per project or task (a running timer counts up to now)."""
    return {r[0]: r[1] for r in db.execute(
        f"SELECT {column}, SUM(COALESCE(end_ts, ?) - start_ts) FROM time_entries WHERE user_id=? GROUP BY {column}",
        (now_ts(), uid))}


def kick_google(c):
    c.after.append(lambda: GSYNC and GSYNC.kick(c.uid))


# --- preferences for the time and planner views

def user_prefs(db, uid):
    out = dict(DEFAULT_PREFS)
    try:
        out.update(json.loads(profile_row(db, uid)["prefs"] or "{}"))
    except ValueError:
        pass
    return out


@route("GET", "/api/prefs")
def api_prefs(c):
    return user_prefs(c.db, c.uid)


@route("PUT", "/api/prefs")
def api_prefs_save(c):
    p, b = user_prefs(c.db, c.uid), c.body
    if "work_goal" in b:
        p["work_goal"] = round(number(b["work_goal"], "Daily work goal", 0, 24), 2)
    if "week_start" in b:
        if b["week_start"] not in WEEK_STARTS:
            raise ApiError(400, "Invalid first day of the week")
        p["week_start"] = b["week_start"]
    if "calendar" in b:
        if b["calendar"] not in ("gregorian", "jalali"):
            raise ApiError(400, "Invalid calendar")
        p["calendar"] = b["calendar"]
    c.db.execute("UPDATE profiles SET prefs=? WHERE user_id=?", (json.dumps(p), c.uid))
    return p


# --- projects

def project_json(r, total=0, open_tasks=0):
    return {"id": r["id"], "name": r["name"], "color": r["color"], "archived": bool(r["archived"]),
            "total": total or 0, "open_tasks": open_tasks or 0}


def _project_values(db, uid, b, old=None):
    name = text(b.get("name", old["name"] if old else None), "Project name", 80)
    color = b.get("color") or (old["color"] if old else None)
    if color is None:  # the first color no other active project uses
        used = {r[0] for r in db.execute("SELECT color FROM projects WHERE user_id=? AND archived=0", (uid,))}
        color = next((x for x in PROJECT_COLORS if x not in used), PROJECT_COLORS[0])
    if not isinstance(color, str) or not COLOR_RE.match(color):
        raise ApiError(400, "Invalid color")
    archived = b["archived"] if "archived" in b else (old["archived"] if old else False)
    return name, color.lower(), 1 if archived else 0


@route("GET", "/api/projects")
def api_projects(c):
    totals = sums_by(c.db, "project_id", c.uid)
    open_tasks = {r[0]: r[1] for r in c.db.execute(
        "SELECT project_id, COUNT(*) FROM tasks WHERE user_id=? AND done=0 GROUP BY project_id", (c.uid,))}
    rows = c.db.execute("SELECT * FROM projects WHERE user_id=? ORDER BY archived, name COLLATE NOCASE", (c.uid,))
    return {"projects": [project_json(r, totals.get(r["id"]), open_tasks.get(r["id"])) for r in rows],
            "unassigned": totals.get(None, 0)}


@route("POST", "/api/projects")
def api_project_add(c):
    name, color, archived = _project_values(c.db, c.uid, c.body)
    cur = c.db.execute("INSERT INTO projects (user_id, name, color, archived, created_at) VALUES (?,?,?,?,?)",
                       (c.uid, name, color, archived, now_ts()))
    return project_json(owned(c.db, "projects", c.uid, cur.lastrowid, "Project"))


@route("PATCH", r"/api/projects/(\d+)")
def api_project_edit(c, pid):
    old = owned(c.db, "projects", c.uid, int(pid), "Project")
    name, color, archived = _project_values(c.db, c.uid, c.body, old)
    c.db.execute("UPDATE projects SET name=?, color=?, archived=? WHERE id=?", (name, color, archived, old["id"]))
    if (name, color) != (old["name"], old["color"]):  # calendar events show the project's name and color
        c.db.execute("UPDATE plan_blocks SET gcal_dirty=1, updated_at=? WHERE user_id=? AND project_id=?",
                     (now_ts(), c.uid, old["id"]))
        kick_google(c)
    totals = sums_by(c.db, "project_id", c.uid)
    return project_json(owned(c.db, "projects", c.uid, old["id"], "Project"), totals.get(old["id"]))


@route("DELETE", r"/api/projects/(\d+)")
def api_project_delete(c, pid):
    p = owned(c.db, "projects", c.uid, int(pid), "Project")
    # Logged time and planned blocks stay (without a project); the project's to-dos go with it.
    c.db.execute("UPDATE plan_blocks SET gcal_dirty=1, updated_at=? WHERE user_id=? AND project_id=?",
                 (now_ts(), c.uid, p["id"]))
    tasks = c.db.execute("DELETE FROM tasks WHERE user_id=? AND project_id=?", (c.uid, p["id"])).rowcount
    c.db.execute("DELETE FROM projects WHERE id=?", (p["id"],))
    kick_google(c)
    return {"ok": True, "deleted_tasks": tasks}


# --- to-dos (tasks). A task can belong to a project, and time can be logged on it.

def task_json(r, total=0):
    return {"id": r["id"], "project_id": r["project_id"], "title": r["title"], "notes": r["notes"],
            "due": r["due"], "priority": r["priority"], "done": bool(r["done"]), "done_at": r["done_at"],
            "created_at": r["created_at"], "total": total or 0}


def _task_values(db, uid, b, old=None):
    def get(key, default=None):
        return b[key] if key in b else (old[key] if old is not None else default)

    title = text(get("title"), "Title", 200)
    notes = long_text(get("notes", ""), "Notes", 2000)
    due = get("due")
    due = parse_date(due, "due date").isoformat() if due else None
    priority = int(number(get("priority", 0) or 0, "Priority", 0, 3))
    project_id = ref_id(get("project_id"), "project")
    if project_id is not None:
        owned(db, "projects", uid, project_id, "Project")
    return {"title": title, "notes": notes, "due": due, "priority": priority, "project_id": project_id}


@route("GET", "/api/tasks")
def api_tasks(c):
    """Open tasks, plus the ones finished in the last `done_days` days (default 14)."""
    since = now_ts() - int(number(c.q("done_days", "14"), "done_days", 0, 3650)) * 86400
    totals = sums_by(c.db, "task_id", c.uid)
    rows = c.db.execute("SELECT * FROM tasks WHERE user_id=? AND (done=0 OR done_at>=?) "
                        "ORDER BY done, due IS NULL, due, priority DESC, id", (c.uid, since))
    return {"tasks": [task_json(r, totals.get(r["id"])) for r in rows]}


@route("POST", "/api/tasks")
def api_task_add(c):
    v = _task_values(c.db, c.uid, c.body)
    cur = c.db.execute("INSERT INTO tasks (user_id, project_id, title, notes, due, priority, created_at) "
                       "VALUES (?,?,?,?,?,?,?)",
                       (c.uid, v["project_id"], v["title"], v["notes"], v["due"], v["priority"], now_ts()))
    return task_json(owned(c.db, "tasks", c.uid, cur.lastrowid, "Task"))


@route("PATCH", r"/api/tasks/(\d+)")
def api_task_edit(c, tid):
    old = owned(c.db, "tasks", c.uid, int(tid), "Task")
    v = _task_values(c.db, c.uid, c.body, old)
    done = bool(c.body["done"]) if "done" in c.body else bool(old["done"])
    done_at = (old["done_at"] or now_ts()) if done and old["done"] else (now_ts() if done else None)
    c.db.execute("UPDATE tasks SET project_id=?, title=?, notes=?, due=?, priority=?, done=?, done_at=? WHERE id=?",
                 (v["project_id"], v["title"], v["notes"], v["due"], v["priority"], int(done), done_at, old["id"]))
    return task_json(owned(c.db, "tasks", c.uid, old["id"], "Task"), sums_by(c.db, "task_id", c.uid).get(old["id"]))


@route("DELETE", r"/api/tasks/(\d+)")
def api_task_delete(c, tid):
    owned(c.db, "tasks", c.uid, int(tid), "Task")
    c.db.execute("DELETE FROM tasks WHERE id=?", (int(tid),))  # logged time stays, without the task
    return {"ok": True}


# --- time entries and the timer

def time_json(r):
    return {k: r[k] for k in ("id", "project_id", "task_id", "description", "start_ts", "end_ts")}


def running_entry(db, uid):
    r = db.execute("SELECT * FROM time_entries WHERE user_id=? AND end_ts IS NULL ORDER BY start_ts DESC LIMIT 1",
                   (uid,)).fetchone()
    return time_json(r) if r else None


@route("GET", "/api/time/entries")
def api_time_entries(c):
    """Entries that overlap [from, to), newest first, and the running timer."""
    frm, to = ts_value(c.q("from"), "from"), ts_value(c.q("to"), "to")
    rows = c.db.execute("SELECT * FROM time_entries WHERE user_id=? AND start_ts < ? AND COALESCE(end_ts, ?) > ? "
                        "ORDER BY start_ts DESC LIMIT 10000", (c.uid, to, now_ts(), frm))
    return {"entries": [time_json(r) for r in rows], "running": running_entry(c.db, c.uid), "now": now_ts()}


@route("GET", "/api/time/timer")
def api_timer(c):
    return {"running": running_entry(c.db, c.uid), "now": now_ts()}


@route("POST", "/api/time/timer/start")
def api_timer_start(c):
    """Start the timer. A timer that's already running stops first."""
    b = c.body
    pid, tid = project_task_refs(c.db, c.uid, ref_id(b.get("project_id"), "project"), ref_id(b.get("task_id"), "task"))
    desc = text(b.get("description"), "Description", 300, required=False)
    t = now_ts()
    c.db.execute("UPDATE time_entries SET end_ts=MAX(?, start_ts + 1) WHERE user_id=? AND end_ts IS NULL", (t, c.uid))
    c.db.execute("INSERT INTO time_entries (user_id, project_id, task_id, description, start_ts) VALUES (?,?,?,?,?)",
                 (c.uid, pid, tid, desc, t))
    return {"running": running_entry(c.db, c.uid), "now": t}


@route("POST", "/api/time/timer/stop")
def api_timer_stop(c):
    r = running_entry(c.db, c.uid)
    if not r:
        return {"stopped": None}
    c.db.execute("UPDATE time_entries SET end_ts=MAX(?, start_ts + 1) WHERE id=?", (now_ts(), r["id"]))
    return {"stopped": time_json(owned(c.db, "time_entries", c.uid, r["id"], "Entry"))}


def _check_span(start, end, running=False):
    if running:
        if start > now_ts() + 60:
            raise ApiError(400, "A running timer can't start in the future")
        return
    if end <= start:
        raise ApiError(400, "End must be after start")
    if end - start > MAX_ENTRY_SECONDS:
        raise ApiError(400, "An entry can be at most 7 days long")


@route("POST", "/api/time/entries")
def api_time_add(c):
    b = c.body
    pid, tid = project_task_refs(c.db, c.uid, ref_id(b.get("project_id"), "project"), ref_id(b.get("task_id"), "task"))
    start, end = ts_value(b.get("start_ts"), "Start"), ts_value(b.get("end_ts"), "End")
    _check_span(start, end)
    cur = c.db.execute("INSERT INTO time_entries (user_id, project_id, task_id, description, start_ts, end_ts) "
                       "VALUES (?,?,?,?,?,?)",
                       (c.uid, pid, tid, text(b.get("description"), "Description", 300, required=False), start, end))
    return time_json(owned(c.db, "time_entries", c.uid, cur.lastrowid, "Entry"))


@route("PATCH", r"/api/time/entries/(\d+)")
def api_time_edit(c, eid):
    e = dict(owned(c.db, "time_entries", c.uid, int(eid), "Entry"))
    b = c.body
    if "description" in b:
        e["description"] = text(b["description"], "Description", 300, required=False)
    if "project_id" in b or "task_id" in b:
        e["project_id"], e["task_id"] = project_task_refs(
            c.db, c.uid, ref_id(b.get("project_id", e["project_id"]), "project"),
            ref_id(b.get("task_id", e["task_id"]), "task"))
    if "start_ts" in b:
        e["start_ts"] = ts_value(b["start_ts"], "Start")
    if "end_ts" in b and e["end_ts"] is not None:  # a running entry ends by stopping the timer
        e["end_ts"] = ts_value(b["end_ts"], "End")
    _check_span(e["start_ts"], e["end_ts"], running=e["end_ts"] is None)
    c.db.execute("UPDATE time_entries SET project_id=?, task_id=?, description=?, start_ts=?, end_ts=? WHERE id=?",
                 (e["project_id"], e["task_id"], e["description"], e["start_ts"], e["end_ts"], e["id"]))
    return time_json(e)


@route("DELETE", r"/api/time/entries/(\d+)")
def api_time_delete(c, eid):
    owned(c.db, "time_entries", c.uid, int(eid), "Entry")
    c.db.execute("DELETE FROM time_entries WHERE id=?", (int(eid),))
    return {"ok": True}


# --- planner: time blocks you schedule, mirrored to Google Calendar when connected

def block_json(r, google=False):
    out = {k: r[k] for k in ("id", "title", "notes", "project_id", "task_id", "start_ts", "end_ts", "series")}
    if google:
        out["gcal"] = "error" if r["gcal_error"] else ("pending" if r["gcal_dirty"] else "synced")
        out["gcal_error"] = r["gcal_error"]
    return out


def has_google(db, uid):
    return db.execute("SELECT 1 FROM google_accounts WHERE user_id=?", (uid,)).fetchone() is not None


def _block_values(db, uid, b, old=None):
    def get(key, default=None):
        return b[key] if key in b else (old[key] if old is not None else default)

    title = text(get("title", ""), "Title", 200, required=False)
    notes = long_text(get("notes", ""), "Notes", 2000)
    pid, tid = project_task_refs(db, uid, ref_id(get("project_id"), "project"), ref_id(get("task_id"), "task"))
    start, end = ts_value(get("start_ts"), "Start"), ts_value(get("end_ts"), "End")
    if end <= start:
        raise ApiError(400, "End must be after start")
    if end - start > 86400:
        raise ApiError(400, "A block can be at most 24 hours long")
    if not title and pid is None and tid is None:
        raise ApiError(400, "Give the block a title or pick a project")
    return {"title": title, "notes": notes, "project_id": pid, "task_id": tid, "start_ts": start, "end_ts": end}


@route("GET", "/api/plan")
def api_plan(c):
    frm, to = ts_value(c.q("from"), "from"), ts_value(c.q("to"), "to")
    google = has_google(c.db, c.uid)
    rows = c.db.execute("SELECT * FROM plan_blocks WHERE user_id=? AND start_ts < ? AND end_ts > ? ORDER BY start_ts",
                        (c.uid, to, frm))
    return {"blocks": [block_json(r, google) for r in rows], "google": google}


@route("POST", "/api/plan")
def api_plan_add(c):
    """Add a block. `repeat_starts` (start times) adds copies of it, for repeating plans."""
    v = _block_values(c.db, c.uid, c.body)
    extra = c.body.get("repeat_starts") or []
    if not isinstance(extra, list) or len(extra) > 100:
        raise ApiError(400, "Too many repeats (at most 100)")
    starts = [v["start_ts"]] + [ts_value(s, "Repeat start") for s in extra]
    series = secrets.token_hex(6) if len(starts) > 1 else None
    length, t, ids = v["end_ts"] - v["start_ts"], now_ts(), []
    for s in sorted(set(starts)):
        cur = c.db.execute("INSERT INTO plan_blocks (user_id, title, notes, project_id, task_id, start_ts, end_ts, "
                           "series, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                           (c.uid, v["title"], v["notes"], v["project_id"], v["task_id"], s, s + length, series, t))
        ids.append(cur.lastrowid)
    kick_google(c)
    google = has_google(c.db, c.uid)
    return {"ids": ids, "block": block_json(owned(c.db, "plan_blocks", c.uid, ids[0], "Block"), google)}


@route("PATCH", r"/api/plan/(\d+)")
def api_plan_edit(c, bid):
    old = owned(c.db, "plan_blocks", c.uid, int(bid), "Block")
    v = _block_values(c.db, c.uid, c.body, old)
    c.db.execute("UPDATE plan_blocks SET title=?, notes=?, project_id=?, task_id=?, start_ts=?, end_ts=?, "
                 "gcal_dirty=1, updated_at=? WHERE id=?",
                 (v["title"], v["notes"], v["project_id"], v["task_id"], v["start_ts"], v["end_ts"], now_ts(),
                  old["id"]))
    kick_google(c)
    return block_json(owned(c.db, "plan_blocks", c.uid, old["id"], "Block"), has_google(c.db, c.uid))


def _delete_blocks(db, uid, rows):
    for r in rows:
        if r["gcal_id"]:
            db.execute("INSERT OR IGNORE INTO gcal_deletes (user_id, cal, event_id) VALUES (?,?,?)",
                       (uid, r["gcal_cal"], r["gcal_id"]))
        db.execute("DELETE FROM plan_blocks WHERE id=?", (r["id"],))
    return len(rows)


@route("DELETE", r"/api/plan/(\d+)")
def api_plan_delete(c, bid):
    """Delete a block; with ?following=1 also the later blocks of the same repeating series."""
    b = owned(c.db, "plan_blocks", c.uid, int(bid), "Block")
    rows = [b]
    if c.q("following") == "1" and b["series"]:
        rows = c.db.execute("SELECT * FROM plan_blocks WHERE user_id=? AND series=? AND start_ts>=?",
                            (c.uid, b["series"], b["start_ts"])).fetchall()
    n = _delete_blocks(c.db, c.uid, rows)
    kick_google(c)
    return {"ok": True, "deleted": n}


# ---------------------------------------------------------------- Google Calendar
#
# Planned blocks are pushed to one calendar the user picks (in the background, retried until they get through).
# Events from the calendars they show in Google appear read-only in the planner, and moving or deleting one of
# our events in Google changes the block here too.

GSYNC = None  # GoogleSync worker, started in main() when Google is configured
# Google's event colors, to show each block in its project's color.
GCAL_COLORS = {"1": "#7986cb", "2": "#33b679", "3": "#8e24aa", "4": "#e67c73", "5": "#f6bf26", "6": "#f4511e",
               "7": "#039be5", "8": "#616161", "9": "#3f51b5", "10": "#0b8043", "11": "#d50000"}
_calendar_cache = {}  # uid -> (expires, calendars)
_cache_lock = threading.Lock()


class GoogleError(Exception):
    def __init__(self, status, message, reauth=False):
        super().__init__(message)
        self.status, self.message, self.reauth = status, message, reauth


def google_http(method, url, token=None, body=None, form=None, params=None, timeout=20):
    if params:
        url += ("&" if "?" in url else "?") + urlencode(params)
    headers, data = {"Accept": "application/json"}, None
    if form is not None:
        data = urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            raw = res.read()
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        raw = e.read()
        e.close()
        msg, reauth = f"Google answered {e.code}", e.code == 401
        try:
            j = json.loads(raw)
            err = j.get("error")
            if isinstance(err, dict):
                msg = err.get("message") or msg
            elif isinstance(err, str):
                msg = j.get("error_description") or err
                reauth = reauth or err in ("invalid_grant", "unauthorized_client")
        except ValueError:
            pass
        raise GoogleError(e.code, msg, reauth)
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise GoogleError(0, f"Can't reach Google ({getattr(e, 'reason', e)})")


def public_base(handler):
    if PUBLIC_URL:
        return PUBLIC_URL
    host = handler.headers.get("X-Forwarded-Host") or handler.headers.get("Host") or f"localhost:{PORT}"
    proto = handler.headers.get("X-Forwarded-Proto", "").split(",")[0].strip().lower()
    if proto not in ("http", "https"):
        proto = "https" if COOKIE_SECURE == "1" else "http"
    return f"{proto}://{host.split(',')[0].strip()}"


def google_account(db, uid):
    return db.execute("SELECT * FROM google_accounts WHERE user_id=?", (uid,)).fetchone()


def google_token(db, uid, force=False):
    """A valid access token, refreshed when needed. Commits the new token."""
    acc = google_account(db, uid)
    if not acc:
        raise GoogleError(0, "Google Calendar isn't connected")
    if acc["needs_reauth"]:
        raise GoogleError(401, "Google access ended. Connect Google Calendar again in Settings.", True)
    if not force and acc["access_token"] and acc["access_expires"] > now_ts() + 60:
        return acc["access_token"]
    try:
        data = google_http("POST", GOOGLE_TOKEN_URL, form={
            "client_id": GOOGLE_CLIENT_ID, "client_secret": GOOGLE_CLIENT_SECRET,
            "refresh_token": acc["refresh_token"], "grant_type": "refresh_token"})
    except GoogleError as e:
        if e.reauth or e.status in (400, 401):
            db.execute("UPDATE google_accounts SET needs_reauth=1, access_token=NULL, last_error=? WHERE user_id=?",
                       ("Google access ended. Connect Google Calendar again in Settings.", uid))
            db.commit()
            raise GoogleError(401, "Google access ended. Connect Google Calendar again in Settings.", True)
        raise
    db.execute("UPDATE google_accounts SET access_token=?, access_expires=? WHERE user_id=?",
               (data["access_token"], now_ts() + int(data.get("expires_in", 3600)), uid))
    db.commit()
    return data["access_token"]


def gcal(db, uid, method, path, **kw):
    """Call the Calendar API as this user; retries once with a fresh token if Google rejects the old one."""
    token = google_token(db, uid)
    try:
        return google_http(method, GOOGLE_API + path, token=token, **kw)
    except GoogleError as e:
        if e.status != 401:
            raise
    return google_http(method, GOOGLE_API + path, token=google_token(db, uid, force=True), **kw)


def rfc3339(ts):
    return dt.datetime.fromtimestamp(ts, dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_rfc3339(s):
    return int(dt.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp())


def nearest_gcal_color(hex_color):
    def rgb(h):
        return int(h[1:3], 16), int(h[3:5], 16), int(h[5:7], 16)
    r, g, b = rgb(hex_color)
    return min(GCAL_COLORS, key=lambda k: sum((x - y) ** 2 for x, y in zip((r, g, b), rgb(GCAL_COLORS[k]))))


def block_summary(db, r):
    if r["title"]:
        return r["title"]
    if r["task_id"]:
        t = db.execute("SELECT title FROM tasks WHERE id=?", (r["task_id"],)).fetchone()
        if t:
            return t["title"]
    if r["project_id"]:
        p = db.execute("SELECT name FROM projects WHERE id=?", (r["project_id"],)).fetchone()
        if p:
            return p["name"]
    return "Focus time"


def block_event(db, uid, r):
    p = db.execute("SELECT name, color FROM projects WHERE id=?", (r["project_id"],)).fetchone() \
        if r["project_id"] else None
    t = db.execute("SELECT title FROM tasks WHERE id=?", (r["task_id"],)).fetchone() if r["task_id"] else None
    lines = [r["notes"]] if r["notes"] else []
    if p:
        lines.append(f"Project: {p['name']}" + (f" › {t['title']}" if t else ""))
    elif t:
        lines.append(f"Task: {t['title']}")
    ev = {"summary": block_summary(db, r), "description": "\n".join(lines),
          "start": {"dateTime": rfc3339(r["start_ts"])}, "end": {"dateTime": rfc3339(r["end_ts"])},
          "extendedProperties": {"private": {"ctBlock": str(r["id"]), "ctUser": str(uid)}}}
    if p:
        ev["colorId"] = nearest_gcal_color(p["color"])
    return ev


def gcal_sync_user(uid):
    """Push this user's pending deletes and changed blocks to Google. Returns how many blocks were pushed."""
    if GSYNC is None:
        return 0
    with GSYNC.user_lock(uid):
        db = connect()
        try:
            return _gcal_push(db, uid)
        except GoogleError as e:
            if google_account(db, uid):
                db.execute("UPDATE google_accounts SET last_error=? WHERE user_id=?", (e.message, uid))
                db.commit()
            raise
        finally:
            db.close()


def _gcal_push(db, uid):
    acc = google_account(db, uid)
    if not acc:
        return 0
    if acc["needs_reauth"]:
        raise GoogleError(401, "Google access ended. Connect Google Calendar again in Settings.", True)
    cal = acc["calendar_id"]
    _gcal_deletes(db, uid)
    pushed = 0
    since = now_ts() - 30 * 86400  # older plans aren't worth sending
    for r in db.execute("SELECT * FROM plan_blocks WHERE user_id=? AND gcal_dirty=1 AND end_ts>? ORDER BY start_ts",
                        (uid, since)).fetchall():
        body = block_event(db, uid, r)
        cal_q = quote(cal, safe="")
        ev = None
        try:
            if r["gcal_id"] and r["gcal_cal"] == cal:
                try:
                    ev = gcal(db, uid, "PUT", f"/calendars/{cal_q}/events/{quote(r['gcal_id'], safe='')}", body=body)
                except GoogleError as e:
                    if e.status not in (404, 410):
                        raise
            elif r["gcal_id"]:  # the target calendar changed: delete the copy in the old one
                db.execute("INSERT OR IGNORE INTO gcal_deletes (user_id, cal, event_id) VALUES (?,?,?)",
                           (uid, r["gcal_cal"], r["gcal_id"]))
                db.execute("UPDATE plan_blocks SET gcal_id=NULL, gcal_cal=NULL WHERE id=?", (r["id"],))
                db.commit()
            if ev is None:
                ev = gcal(db, uid, "POST", f"/calendars/{cal_q}/events", body=body)
        except GoogleError as e:
            if e.status in (0, 401) or e.status >= 500:  # Google is unreachable or access is gone: try later
                raise
            db.execute("UPDATE plan_blocks SET gcal_error=? WHERE id=?", (e.message[:300], r["id"]))
            db.commit()
            continue
        cur = db.execute("UPDATE plan_blocks SET gcal_id=?, gcal_cal=?, gcal_pushed=?, gcal_error=NULL WHERE id=?",
                         (ev["id"], cal, ev.get("updated"), r["id"]))
        if not cur.rowcount:  # deleted while we were sending it
            db.execute("INSERT OR IGNORE INTO gcal_deletes (user_id, cal, event_id) VALUES (?,?,?)", (uid, cal, ev["id"]))
        db.execute("UPDATE plan_blocks SET gcal_dirty=0 WHERE id=? AND updated_at=?", (r["id"], r["updated_at"]))
        db.commit()
        pushed += 1
    _gcal_deletes(db, uid)  # the ones queued while pushing (moved or vanished blocks)
    db.execute("UPDATE google_accounts SET last_sync=?, last_error=NULL WHERE user_id=?", (now_ts(), uid))
    db.commit()
    return pushed


def _gcal_deletes(db, uid):
    for d in db.execute("SELECT cal, event_id FROM gcal_deletes WHERE user_id=?", (uid,)).fetchall():
        try:
            gcal(db, uid, "DELETE", f"/calendars/{quote(d['cal'], safe='')}/events/{quote(d['event_id'], safe='')}")
        except GoogleError as e:
            if e.status not in (404, 410):  # already gone is fine
                raise
        db.execute("DELETE FROM gcal_deletes WHERE user_id=? AND cal=? AND event_id=?", (uid, d["cal"], d["event_id"]))
        db.commit()


class GoogleSync:
    """Background worker: pushes changes soon after they happen and retries failures every few minutes."""

    def __init__(self):
        self.wake = threading.Event()
        self.lock = threading.Lock()
        self.pending = set()
        self.locks = {}

    def user_lock(self, uid):
        with self.lock:
            return self.locks.setdefault(uid, threading.Lock())

    def kick(self, uid):
        with self.lock:
            self.pending.add(uid)
        self.wake.set()

    def start(self):
        threading.Thread(target=self.run, daemon=True, name="google-sync").start()
        return self

    def run(self):
        while True:
            self.wake.wait(timeout=300)
            self.wake.clear()
            time.sleep(1)  # let a burst of edits settle
            with self.lock:
                users, self.pending = self.pending, set()
            if not users:
                db = connect()
                try:
                    users = {r[0] for r in db.execute(
                        "SELECT a.user_id FROM google_accounts a WHERE a.needs_reauth=0 AND ("
                        "EXISTS (SELECT 1 FROM plan_blocks b WHERE b.user_id=a.user_id AND b.gcal_dirty=1 "
                        "AND b.end_ts>?) OR EXISTS (SELECT 1 FROM gcal_deletes d WHERE d.user_id=a.user_id))",
                        (now_ts() - 30 * 86400,))}
                finally:
                    db.close()
            for uid in users:
                try:
                    gcal_sync_user(uid)
                except GoogleError as e:
                    sys.stderr.write(f"google sync for user {uid}: {e.message}\n")
                except Exception:
                    traceback.print_exc()


def google_calendars(db, uid, fresh=False):
    with _cache_lock:
        hit = _calendar_cache.get(uid)
    if hit and hit[0] > time.time() and not fresh:
        return hit[1]
    items = gcal(db, uid, "GET", "/users/me/calendarList", params={"maxResults": 250}).get("items", [])
    cals = [{"id": x["id"], "name": x.get("summaryOverride") or x.get("summary") or x["id"],
             "color": x.get("backgroundColor") or "#888888", "primary": bool(x.get("primary")),
             "shown": bool(x.get("selected")), "writable": x.get("accessRole") in ("owner", "writer")}
            for x in items if not x.get("deleted")]
    with _cache_lock:
        _calendar_cache[uid] = (time.time() + 600, cals)
    return cals


def google_status(db, uid, handler):
    acc = google_account(db, uid)
    out = {"available": bool(GOOGLE_CLIENT_ID), "connected": bool(acc),
           "redirect_uri": public_base(handler) + "/api/google/callback"}
    if acc:
        out.update(email=acc["email"], calendar_id=acc["calendar_id"], show_events=bool(acc["show_events"]),
                   last_sync=acc["last_sync"], last_error=acc["last_error"], needs_reauth=bool(acc["needs_reauth"]),
                   pending=db.execute("SELECT COUNT(*) FROM plan_blocks WHERE user_id=? AND gcal_dirty=1 AND end_ts>?",
                                      (uid, now_ts() - 30 * 86400)).fetchone()[0])
    return out


@route("GET", "/api/google")
def api_google(c):
    return google_status(c.db, c.uid, c.handler)


@route("GET", "/api/google/calendars")
def api_google_calendars(c):
    try:
        return {"calendars": google_calendars(c.db, c.uid, fresh=c.q("fresh") == "1")}
    except GoogleError as e:
        raise ApiError(502 if e.status != 401 else 400, e.message)


@route("POST", "/api/google/connect")
def api_google_connect(c):
    """Start connecting: returns the Google page to open (in the browser, also from the Android app)."""
    if not (GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET):
        raise ApiError(400, "Google Calendar isn't set up on this server yet (GOOGLE_CLIENT_ID, see README).")
    state = secrets.token_urlsafe(24)
    redirect = public_base(c.handler) + "/api/google/callback"
    c.db.execute("DELETE FROM oauth_states WHERE expires < ?", (now_ts(),))
    c.db.execute("INSERT INTO oauth_states (state, user_id, redirect_uri, expires) VALUES (?,?,?,?)",
                 (state, c.uid, redirect, now_ts() + 900))
    return {"url": GOOGLE_AUTH_URL + "?" + urlencode({
        "client_id": GOOGLE_CLIENT_ID, "redirect_uri": redirect, "response_type": "code", "scope": GOOGLE_SCOPES,
        "access_type": "offline", "prompt": "consent", "include_granted_scopes": "true", "state": state})}


def _result_page(title, message, ok):
    color = "#1baf7a" if ok else "#d03b3b"
    page = f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>{html_escape(title)}</title>
<style>body{{font:16px/1.5 system-ui,sans-serif;display:grid;place-items:center;min-height:90vh;margin:0;
background:#f4f4f1;color:#0b0b0b}}main{{max-width:420px;padding:24px;text-align:center}}
h1{{font-size:20px;color:{color}}}a{{color:#2a78d6}}</style></head>
<body><main><h1>{html_escape(title)}</h1><p>{html_escape(message)}</p>
<p><a href="/#settings">Open the web app</a> · or switch back to the Android app.</p></main></body></html>"""
    return RawResponse(page.encode("utf-8"), "text/html; charset=utf-8")


@route("GET", "/api/google/callback", auth=False)
def api_google_callback(c):
    state = c.q("state") or ""
    row = c.db.execute("SELECT * FROM oauth_states WHERE state=?", (state,)).fetchone()
    c.db.execute("DELETE FROM oauth_states WHERE state=?", (state,))
    if not row or row["expires"] < now_ts():
        return _result_page("Link expired", "Start connecting again from Settings.", False)
    if c.q("error"):
        return _result_page("Not connected", f"Google said: {c.q('error')}.", False)
    uid = row["user_id"]
    try:
        tok = google_http("POST", GOOGLE_TOKEN_URL, form={
            "code": c.q("code") or "", "client_id": GOOGLE_CLIENT_ID, "client_secret": GOOGLE_CLIENT_SECRET,
            "redirect_uri": row["redirect_uri"], "grant_type": "authorization_code"})
        if not tok.get("refresh_token"):
            return _result_page("Not connected", "Google didn't grant offline access. Please try again.", False)
        primary = google_http("GET", GOOGLE_API + "/calendars/primary", token=tok["access_token"])
    except GoogleError as e:
        return _result_page("Not connected", e.message, False)
    old = google_account(c.db, uid)
    calendar_id = old["calendar_id"] if old and old["email"] == primary.get("id") else primary.get("id", "primary")
    c.db.execute("INSERT OR REPLACE INTO google_accounts (user_id, refresh_token, access_token, access_expires, email, "
                 "calendar_id, show_events, connected_at) VALUES (?,?,?,?,?,?,?,?)",
                 (uid, tok["refresh_token"], tok["access_token"], now_ts() + int(tok.get("expires_in", 3600)),
                  primary.get("id"), calendar_id, old["show_events"] if old else 1, now_ts()))
    c.db.execute("UPDATE plan_blocks SET gcal_dirty=1 WHERE user_id=? AND end_ts>?", (uid, now_ts() - 30 * 86400))
    with _cache_lock:
        _calendar_cache.pop(uid, None)
    c.after.append(lambda: GSYNC and GSYNC.kick(uid))
    return _result_page("Google Calendar connected",
                        f"Signed in as {primary.get('id', '')}. Your planned blocks will show up in your calendar.",
                        True)


@route("PUT", "/api/google")
def api_google_save(c):
    acc = google_account(c.db, c.uid)
    if not acc:
        raise ApiError(400, "Google Calendar isn't connected")
    if "show_events" in c.body:
        c.db.execute("UPDATE google_accounts SET show_events=? WHERE user_id=?",
                     (1 if c.body["show_events"] else 0, c.uid))
    cal = c.body.get("calendar_id")
    if cal and cal != acc["calendar_id"]:
        try:
            ok = any(x["id"] == cal and x["writable"] for x in google_calendars(c.db, c.uid, fresh=True))
        except GoogleError as e:
            raise ApiError(502, e.message)
        if not ok:
            raise ApiError(400, "You can't add events to that calendar")
        c.db.execute("UPDATE google_accounts SET calendar_id=? WHERE user_id=?", (cal, c.uid))
        c.db.execute("UPDATE plan_blocks SET gcal_dirty=1 WHERE user_id=? AND gcal_id IS NOT NULL", (c.uid,))
        kick_google(c)
    return google_status(c.db, c.uid, c.handler)


@route("POST", "/api/google/sync")
def api_google_sync(c):
    """Push everything now and report back."""
    c.db.commit()  # the worker uses its own connection
    try:
        pushed = gcal_sync_user(c.uid)
    except GoogleError as e:
        raise ApiError(502 if e.status != 401 else 400, e.message)
    return {"pushed": pushed, **google_status(c.db, c.uid, c.handler)}


@route("DELETE", "/api/google")
def api_google_disconnect(c):
    """Forget the Google login. Events already in Google stay there."""
    acc = google_account(c.db, c.uid)
    if acc:
        try:
            google_http("POST", GOOGLE_REVOKE_URL, form={"token": acc["refresh_token"]}, timeout=8)
        except GoogleError:
            pass  # forgetting it here is what matters
        c.db.execute("DELETE FROM google_accounts WHERE user_id=?", (c.uid,))
        c.db.execute("DELETE FROM gcal_deletes WHERE user_id=?", (c.uid,))
        with _cache_lock:
            _calendar_cache.pop(c.uid, None)
    return {"ok": True}


@route("GET", "/api/google/events")
def api_google_events(c):
    """Events from the user's shown Google calendars in [from, to). Also applies changes made in Google
    to our own events (moved, renamed or deleted there) to the matching blocks."""
    frm, to = ts_value(c.q("from"), "from"), ts_value(c.q("to"), "to")
    acc = google_account(c.db, c.uid)
    if not acc or acc["needs_reauth"]:
        return {"connected": bool(acc), "events": [], "changed": 0,
                "error": acc and "Google access ended. Connect again in Settings."}
    try:
        cals = google_calendars(c.db, c.uid)
        wanted = [x for x in cals if (x["shown"] and acc["show_events"]) or x["id"] == acc["calendar_id"]]
        events, changed = [], 0
        for cal in wanted:
            mine = cal["id"] == acc["calendar_id"]
            params = {"timeMin": rfc3339(frm), "timeMax": rfc3339(to), "singleEvents": "true", "maxResults": 250,
                      "orderBy": "startTime"}
            if mine:
                params["showDeleted"] = "true"
            items = gcal(c.db, c.uid, "GET", f"/calendars/{quote(cal['id'], safe='')}/events", params=params)
            for ev in items.get("items", []):
                priv = (ev.get("extendedProperties") or {}).get("private") or {}
                if priv.get("ctUser") == str(c.uid) and priv.get("ctBlock"):
                    if mine:
                        changed += _apply_google_change(c.db, c.uid, ev, priv["ctBlock"])
                    continue
                if ev.get("status") == "cancelled" or not (cal["shown"] and acc["show_events"]):
                    continue
                start, end = ev.get("start") or {}, ev.get("end") or {}
                item = {"id": ev.get("id"), "title": ev.get("summary") or "(busy)", "calendar": cal["name"],
                        "color": cal["color"], "link": ev.get("htmlLink")}
                if "date" in start:
                    item.update(all_day=True, start_date=start["date"], end_date=end.get("date", start["date"]))
                elif "dateTime" in start and "dateTime" in end:
                    item.update(all_day=False, start_ts=parse_rfc3339(start["dateTime"]),
                                end_ts=parse_rfc3339(end["dateTime"]))
                else:
                    continue
                events.append(item)
    except GoogleError as e:
        return {"connected": True, "events": [], "changed": 0, "error": e.message}
    return {"connected": True, "events": events, "changed": changed, "error": None}


def _apply_google_change(db, uid, ev, block_id):
    try:
        b = db.execute("SELECT * FROM plan_blocks WHERE id=? AND user_id=?", (int(block_id), uid)).fetchone()
    except ValueError:
        return 0
    if not b or b["gcal_id"] != ev.get("id") or b["gcal_dirty"]:
        return 0  # not ours anymore, or our own newer change is about to be sent
    if ev.get("updated") and b["gcal_pushed"] and ev["updated"] <= b["gcal_pushed"]:
        return 0
    if ev.get("status") == "cancelled":
        db.execute("DELETE FROM plan_blocks WHERE id=?", (b["id"],))
        return 1
    start, end = (ev.get("start") or {}).get("dateTime"), (ev.get("end") or {}).get("dateTime")
    if not (start and end):
        return 0  # turned into an all-day event; leave the block as it is
    new = {"start_ts": parse_rfc3339(start), "end_ts": parse_rfc3339(end)}
    summary = ev.get("summary") or ""
    title = b["title"] if summary == block_summary(db, b) else summary[:200]
    if (new["start_ts"], new["end_ts"], title) == (b["start_ts"], b["end_ts"], b["title"]):
        db.execute("UPDATE plan_blocks SET gcal_pushed=? WHERE id=?", (ev.get("updated"), b["id"]))
        return 0
    db.execute("UPDATE plan_blocks SET start_ts=?, end_ts=?, title=?, gcal_pushed=?, updated_at=? "
               "WHERE id=? AND gcal_dirty=0",
               (new["start_ts"], new["end_ts"], title, ev.get("updated"), now_ts(), b["id"]))
    return 1


# ---------------------------------------------------------------- calendar feed (ICS)
#
# A secret link any calendar app can subscribe to: your planned blocks and dated to-dos. Read-only and
# refreshed on the calendar app's schedule (Google: every few hours), so the Google connection is quicker.

def ics_token(db, uid, reset=False):
    p = profile_row(db, uid)
    if p["ics_token"] and not reset:
        return p["ics_token"]
    token = secrets.token_urlsafe(24)
    db.execute("UPDATE profiles SET ics_token=? WHERE user_id=?", (token, uid))
    return token


@route("GET", "/api/ics")
def api_ics(c):
    return {"url": f"{public_base(c.handler)}/cal/{ics_token(c.db, c.uid)}.ics"}


@route("POST", "/api/ics/reset")
def api_ics_reset(c):
    return {"url": f"{public_base(c.handler)}/cal/{ics_token(c.db, c.uid, reset=True)}.ics"}


def _ics_text(s):
    return str(s).replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r", "").replace("\n", "\\n")


def _ics_fold(line):
    """Lines longer than 75 bytes continue on the next line after a space (RFC 5545)."""
    raw, out, cur = line.encode("utf-8"), [], b""
    for ch in line:
        b = ch.encode("utf-8")
        if len(cur) + len(b) > (75 if not out else 74):
            out.append(cur)
            cur = b""
        cur += b
    out.append(cur)
    return b"\r\n ".join(out).decode("utf-8") if len(raw) > 75 else line


def build_ics(db, uid):
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    fmt_ts = lambda ts: dt.datetime.fromtimestamp(ts, dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")  # noqa: E731
    lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Daybook//Planner//EN", "CALSCALE:GREGORIAN",
             "X-WR-CALNAME:Planner", "REFRESH-INTERVAL;VALUE=DURATION:PT1H", "X-PUBLISHED-TTL:PT1H"]
    t = now_ts()
    for r in db.execute("SELECT * FROM plan_blocks WHERE user_id=? AND end_ts>? AND start_ts<? ORDER BY start_ts",
                        (uid, t - 60 * 86400, t + 400 * 86400)).fetchall():
        lines += ["BEGIN:VEVENT", f"UID:block-{r['id']}@daybook", f"DTSTAMP:{stamp}",
                  f"DTSTART:{fmt_ts(r['start_ts'])}", f"DTEND:{fmt_ts(r['end_ts'])}",
                  f"SUMMARY:{_ics_text(block_summary(db, r))}"]
        if r["notes"]:
            lines.append(f"DESCRIPTION:{_ics_text(r['notes'])}")
        lines.append("END:VEVENT")
    for r in db.execute("SELECT * FROM tasks WHERE user_id=? AND done=0 AND due IS NOT NULL", (uid,)).fetchall():
        d = dt.date.fromisoformat(r["due"])
        lines += ["BEGIN:VEVENT", f"UID:task-{r['id']}@daybook", f"DTSTAMP:{stamp}",
                  f"DTSTART;VALUE=DATE:{d.strftime('%Y%m%d')}",
                  f"DTEND;VALUE=DATE:{(d + dt.timedelta(days=1)).strftime('%Y%m%d')}",
                  f"SUMMARY:{_ics_text('☐ ' + r['title'])}", "TRANSP:TRANSPARENT", "END:VEVENT"]
    lines.append("END:VCALENDAR")
    return ("\r\n".join(_ics_fold(x) for x in lines) + "\r\n").encode("utf-8")


# ---------------------------------------------------------------- import from Sakura Log

def import_sakura(db, uid, path):
    """Copy projects, tasks and time entries from a Sakura Log database (logger.db) or its JSON backup.
    Safe to run twice: things already here are skipped."""
    if path.endswith(".json"):
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
    else:
        src = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        src.row_factory = sqlite3.Row
        data = {k: [dict(r) for r in src.execute(f"SELECT * FROM {k}")] for k in ("projects", "tasks", "entries")}
        src.close()
    counts = {"projects": 0, "tasks": 0, "entries": 0, "skipped": 0}
    pmap, tmap = {}, {}
    for p in data.get("projects", []):
        row = db.execute("SELECT id FROM projects WHERE user_id=? AND name=? COLLATE NOCASE",
                         (uid, p["name"])).fetchone()
        if row:
            pmap[p["id"]] = row["id"]
            continue
        color = p.get("color") if COLOR_RE.match(str(p.get("color") or "")) else PROJECT_COLORS[0]
        pmap[p["id"]] = db.execute("INSERT INTO projects (user_id, name, color, archived, created_at) "
                                   "VALUES (?,?,?,?,?)", (uid, p["name"][:80], color.lower(), int(bool(p.get("archived"))),
                                                          p.get("created_at") or now_ts())).lastrowid
        counts["projects"] += 1
    for t in data.get("tasks", []):
        pid = pmap.get(t.get("project_id"))
        row = db.execute("SELECT id FROM tasks WHERE user_id=? AND title=? AND project_id IS ?",
                         (uid, t["name"], pid)).fetchone()
        if row:
            tmap[t["id"]] = row["id"]
            continue
        created = t.get("created_at") or now_ts()
        tmap[t["id"]] = db.execute("INSERT INTO tasks (user_id, project_id, title, done, done_at, created_at) "
                                   "VALUES (?,?,?,?,?,?)", (uid, pid, t["name"][:200], int(bool(t.get("done"))),
                                                            created if t.get("done") else None, created)).lastrowid
        counts["tasks"] += 1
    running = running_entry(db, uid)
    for e in data.get("entries", []):
        start, end = e.get("start_ts"), e.get("end_ts")
        if end is None and running:
            end = max(start + 1, now_ts())  # only one timer can run
        if db.execute("SELECT 1 FROM time_entries WHERE user_id=? AND start_ts=? AND end_ts IS ? AND description=?",
                      (uid, start, end, e.get("description") or "")).fetchone():
            counts["skipped"] += 1
            continue
        db.execute("INSERT INTO time_entries (user_id, project_id, task_id, description, start_ts, end_ts) "
                   "VALUES (?,?,?,?,?,?)", (uid, pmap.get(e.get("project_id")), tmap.get(e.get("task_id")),
                                            e.get("description") or "", start, end))
        if end is None:
            running = True
        counts["entries"] += 1
    return counts


# ---------------------------------------------------------------- HTTP handler

CSP = ("default-src 'self'; img-src 'self' data:; style-src 'self' https://fonts.googleapis.com; "
       "font-src https://fonts.gstatic.com; script-src 'self'; connect-src 'self'; "
       "frame-ancestors 'none'; base-uri 'none'; form-action 'self'")


class Handler(BaseHTTPRequestHandler):
    server_version = "Daybook"
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
            if url.path.startswith("/cal/"):
                return self.serve_ics(url.path)
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
            ctx = Ctx(self, db, uid, body, parse_qs(url.query))
            result = fn(ctx, *match.groups())
            db.commit()
            for hook in ctx.after:
                try:
                    hook()
                except Exception:  # a failed side job must not fail the request
                    traceback.print_exc()
            if isinstance(result, RawResponse):
                return self.send(200, result.data, result.ctype, {"Cache-Control": "no-store", **result.headers})
            return self.send_json(200, result)
        except ApiError as e:
            db.rollback()
            return self.send_json(e.status, {"error": e.message})
        except Exception as e:  # keep the server alive, log the bug
            db.rollback()
            self.log_error("error on %s %s: %r", method, url.path, e)
            traceback.print_exc()
            return self.send_json(500, {"error": "Server error"})
        finally:
            db.close()

    def serve_ics(self, path):
        m = re.fullmatch(r"/cal/([\w-]{20,64})\.ics", path)
        if not m:
            return self.send(404, b"Not found", "text/plain; charset=utf-8")
        db = connect()
        try:
            row = db.execute("SELECT user_id FROM profiles WHERE ics_token=?", (m.group(1),)).fetchone()
            data = build_ics(db, row["user_id"]) if row else None
        finally:
            db.close()
        if data is None:
            return self.send(404, b"Not found", "text/plain; charset=utf-8")
        self.send(200, data, "text/calendar; charset=utf-8", {"Cache-Control": "no-cache"})

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
        elif cmd == "import-sakura" and len(argv) == 3:
            row = db.execute("SELECT id FROM users WHERE username=?", (argv[2],)).fetchone()
            if not row:
                sys.exit("No such user")
            if not os.path.isfile(argv[1]):
                sys.exit(f"Not found: {argv[1]}")
            n = import_sakura(db, row["id"], argv[1])
            db.commit()
            print(f"Imported {n['projects']} projects, {n['tasks']} tasks and {n['entries']} time entries "
                  f"for {argv[2]} ({n['skipped']} entries were already here).")
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
    global GSYNC
    if GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET:
        GSYNC = GoogleSync().start()
        GSYNC.wake.set()  # catch up on changes made while the server was down
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    print(f"Daybook running on http://{HOST}:{PORT}  (data: {db_path()})"
          + ("  · Google Calendar on" if GSYNC else ""))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
