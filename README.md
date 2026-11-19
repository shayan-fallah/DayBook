# Daybook

A small self-hosted tracker for food, workouts and your work: calories on the Fig Fit food dataset
(15,854 foods, mostly Persian names), a gym log, a time tracker with projects and reports, a day planner
that syncs with Google Calendar, and a to-do list. It is one Python file with no dependencies, plus a
mobile-friendly web page and an Android app. Your data lives in one SQLite file.

## Features

- **Food search** across the whole dataset, tolerant of Arabic vs Persian letters (ي/ی, ك/ک), half-spaces,
  Persian digits and typos in spacing. Foods you log often and your favorites rise to the top.
- **Real serving units** from the dataset (عدد، لیوان، قاشق غذاخوری، برش…), each shown with its weight in grams,
  plus grams for everything. Amounts accept `1.5`, `۲`, `1/2`.
- **Daily view**: calories left, protein/carbs/fat with targets, fiber, sugar, sodium, broken down by meal.
- **One-tap re-add** of recent foods with the same portion, **favorites**, and **copy yesterday's meal or whole day**.
- **Quick calories** for meals out, and **your own foods** with any number of serving sizes
  ("slice = 30 g", "bowl = 300 g"), entered per 100 g or per serving.
- **Edit any food**, also the ones from the database: name, calories, protein, carbs, fat, fiber, sugar,
  sodium and serving sizes. Your values are only for you (other accounts keep the original) and you can
  reset them. Optionally recalculate the times you already logged it. Open a food from search and tap ✎,
  or look it up under More → Foods.
- **Personal goal**: calories from sex, age, height, weight, activity and a weekly weight goal (Mifflin-St Jeor),
  with an adjustable macro split. Or just type your own number.
- **Water** (glasses) and **weight** logging.
- **Gym tracker**: log bodybuilding, cardio (running, incline walk, cycling, elliptical, rowing, swimming…)
  or any sport with its duration and effort. Pick the muscles you trained, add distance and notes
  ("Bench 80 kg 5×5"), or repeat a recent workout in one tap. Burned calories are estimated from your weight
  and raise the day's calorie budget (all, half or none, your choice).
- **Gym tab**: training days this week against your weekly goal, goal streak in weeks, hours per day or week
  split into strength / cardio / other, when you last trained each muscle group, time per activity and a full
  workout log.
- **History**: calories per day against your goal, weight trend with weeks-to-goal estimate, average macros,
  calories by meal, top calorie sources, logging streak. Ranges: 7, 30, 90 days or 1 year.
- **Time tracker** (Time tab): one-tap timer that keeps running on the server (start on the phone, stop on the
  web), "continue" any past entry, add or fix time by hand. A bar above the tabs shows the running timer
  everywhere. Group time by **projects** (colors, archive) and **tasks**.
- **Reports**: day, week, month or year; hours per day by project, totals, days worked, daily average,
  planned vs. logged, and a per-project breakdown down to tasks. CSV export.
- **Planner** (Plan tab): a day timeline with a week strip. Tap an empty spot to plan time for a project or
  task, repeat it (every day, every work day, every week), and start the timer from a planned block. What you
  actually logged shows as thin bars next to the plan.
- **To-do list**: quick add with due date and project, priorities, notes; grouped into overdue, today,
  upcoming and someday. Start a timer on a to-do or plan time for it in one tap.
- **Google Calendar**: planned blocks appear in a calendar you pick (in the project's color), your Google events
  appear in the planner, and moving or deleting a block in Google changes it here too. See below.
- **Calendar feed**: a private link to subscribe to from any calendar app (planned blocks and dated to-dos).
- **Shamsi (هجری شمسی) dates** in the time and planner views, with Saturday as the first day of the week.
- **CSV export** of your log, weights, workouts and time (opens correctly in Excel with Persian text).
- Accounts with login, dark mode, works on a phone, and you can add it to your home screen like an app.

## Run it locally

```bash
python3 app.py
```

Open http://localhost:8080 and create your account. Needs Python 3.9 or newer, nothing to install.

## Put it on your VPS

Put the code on the server (for example `git clone https://github.com/shayan-fallah/daybook.git /opt/daybook`),
then pick **one** of the options below.

> **Create your account right away.** The first person to open a fresh install gets to create the first
> account. After that, sign-up is closed. Or create it from the shell before you open the port:
> `python3 app.py adduser yourname`.

### Option A: Docker

```bash
cd /opt/daybook
docker compose up -d --build
```

The app listens on `127.0.0.1:8080` and the data is in the `calories-data` Docker volume.

### Option B: systemd (no Docker)

```bash
sudo useradd --system --home /opt/daybook tracker
sudo mkdir -p /opt/daybook/data && sudo chown -R tracker /opt/daybook/data
sudo cp deploy/daybook.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now daybook
```

### HTTPS (recommended)

Both options only listen on the VPS itself. Put [Caddy](https://caddyserver.com/docs/install) in front of it.
Caddy gets a free certificate automatically, so your password doesn't travel over plain HTTP:

1. Point a domain (or subdomain) at your VPS's IP.
2. Edit the domain in `deploy/Caddyfile`, copy it to `/etc/caddy/Caddyfile`, run `sudo systemctl reload caddy`.

No domain? Set `HOST=0.0.0.0` (systemd) or change the port to `"8080:8080"` (Docker) and open
`http://your-vps-ip:8080`. That works, but the connection isn't encrypted.

## Android app

A native Android app (Kotlin + Jetpack Compose) lives in `android/`. It talks to the same server, so the phone
and the web page always show the same data.

**Install:** copy `android/daybook.apk` to your phone and open it. Android will ask you to allow
installing apps from that source (Files, Chrome, Telegram…). Requires Android 8.0 or newer.

**First start:** enter your server address (`daybook.example.com` or `your-vps-ip:8080`), your username and
password. The app remembers the login. Everything from the web version is there: food search with units,
meals, quick re-add, favorites, quick calories, water, weight, workouts and the Gym tab, editing foods,
the timer, projects, reports, the planner and to-dos, history charts and settings (Google Calendar
connects through your browser).

**Rebuild after changing the code** (needs only Docker, no Android Studio):

```bash
cd android && ./build-apk.sh
```

The first build downloads the Android SDK into a Docker image and creates a signing key in `android/signing/`.
**Keep that folder:** Android only installs an update over the existing app when it's signed with the same key.
You can also open `android/` in Android Studio.

The app logs in with a token (`Authorization: Bearer …`) instead of a browser cookie, so it needs this version
of `app.py` on the server.

## Updating

Update the server first, then the phone app (a new app needs the new server; the old app keeps working with
the new server in the meantime).

1. **Server:** copy the new files over the old ones (or `git pull`), then restart:
   `docker compose up -d --build` or `sudo systemctl restart daybook`. Your data is kept; the database
   upgrades itself on start.
2. **Phone:** install the new `android/daybook.apk` over the old one. Don't uninstall first, so you
   stay logged in. It only installs as an update when built with the same `android/signing/` key.

## Google Calendar (optional)

The server talks to Google itself, so you set this up once on the server and then every account can connect
its own Google Calendar from Settings (web or Android). You need a free "OAuth client" from Google:

1. Open [Google Cloud Console](https://console.cloud.google.com/), create a project (any name).
2. **APIs & Services → Library**: enable the **Google Calendar API**.
3. **APIs & Services → OAuth consent screen** (or "Google Auth Platform"): user type **External**, fill in an
   app name and your email. Under **Data access**, add the scopes `.../auth/calendar.events` and
   `.../auth/calendar.readonly`. Then set **Publishing status** to **In production**. (In "Testing" mode Google
   ends the connection after 7 days. You don't need Google's verification for your own use: on the consent
   page click *Advanced → Go to … (unsafe)* once.)
4. **APIs & Services → Credentials → Create credentials → OAuth client ID**, type **Web application**.
   Under *Authorized redirect URIs* add `https://YOUR-DOMAIN/api/google/callback` (Settings → Google
   Calendar on the web page shows the exact address). Google only allows `https://` addresses, or
   `http://localhost` for testing.
5. Give the server the client ID and secret, plus its public address, and restart it:

   ```bash
   # systemd: add to deploy/daybook.service, then daemon-reload and restart
   Environment=PUBLIC_URL=https://daybook.example.com
   Environment=GOOGLE_CLIENT_ID=1234-abc.apps.googleusercontent.com
   Environment=GOOGLE_CLIENT_SECRET=GOCSPX-...
   ```

   With Docker, put the same three in the `environment:` section of `docker-compose.yml`.

6. In the app: **Settings → Google Calendar → Connect**, pick the calendar for your planned blocks.

How it syncs: blocks you plan are sent to Google a moment after you save them (and retried if Google can't be
reached). Events from the calendars you have switched on in Google show up read-only in the planner. If you
move, rename or delete one of the app's events in Google, the block follows. Disconnecting leaves the events
that are already in Google.

No domain or don't want to set this up? **Settings → Calendar feed** gives you a link to subscribe to in Google
Calendar (*Other calendars → From URL*), Apple Calendar or Outlook. It's read-only and Google refreshes it every
few hours.

## Moving over from Sakura Log

Copy Sakura Log's database (`data/logger.db`) or its JSON backup (Settings → Download backup) to the server and
import it into an account. Projects, tasks (they become to-dos) and time entries come over; running it twice
skips what's already there.

```bash
python3 app.py import-sakura /path/to/logger.db yourname
# Docker: docker compose cp logger.db calories:/data/ && docker compose exec calories python3 app.py import-sakura /data/logger.db yourname
```

## Settings (environment variables)

| Variable | Default | What it does |
|---|---|---|
| `HOST` | `0.0.0.0` | Address to listen on (the Docker and systemd setups use localhost behind Caddy) |
| `PORT` | `8080` | Port |
| `DATA_DIR` | `./data` | Where `tracker.db` is stored |
| `ALLOW_SIGNUP` | `0` | `1` lets anyone create an account (for family or friends) |
| `TRUST_PROXY` | `0` | `1` when behind Caddy/nginx, so login rate limiting sees the real IP |
| `COOKIE_SECURE` | `auto` | Secure cookies when the proxy reports HTTPS; `1`/`0` to force |
| `PUBLIC_URL` | – | The address people use, e.g. `https://daybook.example.com` (for Google and the calendar feed link; otherwise taken from the request) |
| `GOOGLE_CLIENT_ID` | – | Google OAuth client ID, turns on Google Calendar (see above) |
| `GOOGLE_CLIENT_SECRET` | – | Its secret |

## Admin commands

```bash
python3 app.py adduser NAME     # create an account (e.g. for a family member)
python3 app.py passwd NAME      # reset a forgotten password
python3 app.py users            # list accounts
python3 app.py backup FILE      # safe copy of the database, fine while the app runs
python3 app.py import-sakura FILE NAME   # import Sakura Log's logger.db or backup .json into an account
```

With Docker, prefix them: `docker compose exec calories python3 app.py users`.

**Backups:** everything is in `tracker.db`. A daily cron job is enough:

```cron
0 3 * * * cd /opt/daybook && python3 app.py backup /opt/backups/daybook-$(date +\%F).db
```

## Notes

- Nutrition values in the dataset are per 100 g. A unit's `multiplier` is how many 100 g portions it weighs,
  so 1 لیوان with multiplier 1.95 is 195 g.
- Each log entry stores its own numbers, so editing or deleting a food never changes your history, unless you
  tick "Also update the times I logged it" in the food editor.
- Edits to database foods are kept per account in their own table, so they survive a dataset re-import.
- Times are stored as UTC timestamps; days, weeks and months follow the timezone of the phone or browser.
- Google Calendar tokens are stored in `tracker.db`; keep backups of it as private as the app itself.
- Workout calories use MET values from the Compendium of Physical Activities: (MET − 1) × body weight × hours.
  The −1 leaves out the calories you'd burn resting anyway, since those are already in your daily goal.
  To add a sport or change a value, edit `ACTIVITIES` in `app.py`; the web page and the app pick it up.
- Older versions had an "Exercise kcal" box. Those numbers become "Exercise" workouts when the new server starts.
- If you replace `foods.csv` / `food_units.csv` with a newer version, the app re-imports them on the next start.
- Tests: `python3 -m unittest discover -s tests -v`

---

# Food Nutrition Dataset

A structured food nutrition dataset published by [Fig Fit](https://figfit.ir), featuring foods from various cuisines with Persian-language food names.

## Files

- `foods.csv` — Food names, nutritional values, and source labels.
- `food_units.csv` — Serving units and their associated food IDs.

## Schema

### `foods.csv`

| Column | Description |
|---|---|
| `id` | Food identifier |
| `name` | Food name |
| `calories` | Calories |
| `fat` | Fat content |
| `carbs` | Carbohydrate content |
| `protein` | Protein content |
| `sodium` | Sodium content |
| `sugar` | Sugar content |
| `fiber` | Dietary fiber content |
| `source` | Source label, when available |

### `food_units.csv`

| Column | Description |
|---|---|
| `food_id` | Associated food ID |
| `unit_id` | Serving unit ID |
| `name` | Unit name |
| `multiplier` | Unit conversion multiplier |

## Usage

Suitable for food search, nutrition applications, data analysis, and Persian-language NLP projects.

Nutritional values, units, and conversion multipliers should be validated before use. Missing values may occur.

## License

Licensed under [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).

Attribution is required. Commercial use is not permitted under this license, and adaptations must be distributed under the same license.

---

Published by [Fig Fit](https://figfit.ir).
