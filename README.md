# Calorie Tracker

A small self-hosted calorie tracker built on the Fig Fit food dataset (15,854 foods, mostly Persian names).
It is one Python file with no dependencies, plus a mobile-friendly web page. Your data lives in one SQLite file.

## Features

- **Food search** across the whole dataset, tolerant of Arabic vs Persian letters (ي/ی, ك/ک), half-spaces,
  Persian digits and typos in spacing. Foods you log often and your favorites rise to the top.
- **Real serving units** from the dataset (عدد، لیوان، قاشق غذاخوری، برش…), each shown with its weight in grams,
  plus grams for everything. Amounts accept `1.5`, `۲`, `1/2`.
- **Daily view**: calories left, protein/carbs/fat with targets, fiber, sugar, sodium, broken down by meal.
- **One-tap re-add** of recent foods with the same portion, **favorites**, and **copy yesterday's meal or whole day**.
- **Quick calories** for meals out, and **custom foods** (enter the label per serving).
- **Personal goal**: calories from sex, age, height, weight, activity and a weekly weight goal (Mifflin-St Jeor),
  with an adjustable macro split. Or just type your own number.
- **Water** (glasses), **exercise** calories (added to the day's budget) and **weight** logging.
- **History**: calories per day against your goal, weight trend with weeks-to-goal estimate, average macros,
  calories by meal, top calorie sources, logging streak. Ranges: 7, 30, 90 days or 1 year.
- **CSV export** of your log and weights (opens correctly in Excel with Persian text).
- Accounts with login, dark mode, works on a phone, and you can add it to your home screen like an app.

## Run it locally

```bash
python3 app.py
```

Open http://localhost:8080 and create your account. Needs Python 3.9 or newer, nothing to install.

## Put it on your VPS

Copy this folder to the server (for example `scp -r calorie-counter you@your-vps:/opt/calorie-tracker`),
then pick **one** of the options below.

> **Create your account right away.** The first person to open a fresh install gets to create the first
> account. After that, sign-up is closed. Or create it from the shell before you open the port:
> `python3 app.py adduser yourname`.

### Option A: Docker

```bash
cd /opt/calorie-tracker
docker compose up -d --build
```

The app listens on `127.0.0.1:8080` and the data is in the `calories-data` Docker volume.

### Option B: systemd (no Docker)

```bash
sudo useradd --system --home /opt/calorie-tracker tracker
sudo mkdir -p /opt/calorie-tracker/data && sudo chown -R tracker /opt/calorie-tracker/data
sudo cp deploy/calorie-tracker.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now calorie-tracker
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

**Install:** copy `android/calorie-tracker.apk` to your phone and open it. Android will ask you to allow
installing apps from that source (Files, Chrome, Telegram…). Requires Android 8.0 or newer.

**First start:** enter your server address (`calories.example.com` or `your-vps-ip:8080`), your username and
password. The app remembers the login. Everything from the web version is there: food search with units,
meals, quick re-add, favorites, quick calories, water, weight, exercise, history charts, custom foods and goals.

**Rebuild after changing the code** (needs only Docker, no Android Studio):

```bash
cd android && ./build-apk.sh
```

The first build downloads the Android SDK into a Docker image and creates a signing key in `android/signing/`.
**Keep that folder:** Android only installs an update over the existing app when it's signed with the same key.
You can also open `android/` in Android Studio.

The app logs in with a token (`Authorization: Bearer …`) instead of a browser cookie, so it needs this version
of `app.py` on the server.

## Settings (environment variables)

| Variable | Default | What it does |
|---|---|---|
| `HOST` | `0.0.0.0` | Address to listen on (the Docker and systemd setups use localhost behind Caddy) |
| `PORT` | `8080` | Port |
| `DATA_DIR` | `./data` | Where `tracker.db` is stored |
| `ALLOW_SIGNUP` | `0` | `1` lets anyone create an account (for family or friends) |
| `TRUST_PROXY` | `0` | `1` when behind Caddy/nginx, so login rate limiting sees the real IP |
| `COOKIE_SECURE` | `auto` | Secure cookies when the proxy reports HTTPS; `1`/`0` to force |

## Admin commands

```bash
python3 app.py adduser NAME     # create an account (e.g. for a family member)
python3 app.py passwd NAME      # reset a forgotten password
python3 app.py users            # list accounts
python3 app.py backup FILE      # safe copy of the database, fine while the app runs
```

With Docker, prefix them: `docker compose exec calories python3 app.py users`.

**Backups:** everything is in `tracker.db`. A daily cron job is enough:

```cron
0 3 * * * cd /opt/calorie-tracker && python3 app.py backup /opt/backups/calories-$(date +\%F).db
```

## Notes

- Nutrition values in the dataset are per 100 g. A unit's `multiplier` is how many 100 g portions it weighs,
  so 1 لیوان with multiplier 1.95 is 195 g.
- Each log entry stores its own numbers, so editing or deleting a custom food never changes your history.
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
