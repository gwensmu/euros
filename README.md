# Euros

A personal Android app for preparing for a BJJ competition: dated training, recovery, meal plans, food logging, tracker workouts and a small weasel companion.

## Who it is for

An individual recreational BJJ athlete preparing for a specific competition, or a developer adapting that workflow for their own training goal. It is designed for a personal Android phone and a self-hosted backend, not a gym management system or a substitute for a coach.

## Architecture

- **Android:** Kotlin, Jetpack Compose, Room and a durable offline write queue.
- **Backend:** Python, Starlette/Uvicorn, one shared PostgreSQL database (SQLite for local development/tests), SQLAlchemy and FastMCP. The phone API and MCP use the same revision-checked domain operations.
- **AI:** server-side food interpretation and constrained reminder-copy selection. Provider credentials never belong in the app.
- **Trackers:** Android Health Connect imports, automatic unambiguous planned-workout linking, and optional food-log export; source identity and missing values are preserved.

This is personal software, not a general coaching service. It contains opinionated example goals, dates, schedules, meal preferences and nutrition policies. Review and customize those before using it. Bundled plans are prescriptions, not records of actual workouts or food intake. New accounts start without logged records or a copied personal schedule. Create dated sessions from your account plan or explicitly import an authored plan.

## Recreate and personalize

The [implementation recipe](docs/shareable/PERSONAL_TRAINING_APP_BLUEPRINT.md) describes the product, interactions, system architecture and personalization workflow. The [standalone illustrated version](docs/shareable/PERSONAL_TRAINING_APP_BLUEPRINT.html) can be downloaded and opened in a browser.

Start with `plan/euros.json`, its Android asset counterpart, the dated schedule assets, and nutrition-policy code/contracts. Account plan settings cover competition, goals, meals and weekly training times; detailed prescriptions and some defaults still live in code. Model names must be available to your own provider account.

## Run the backend

Install Python 3.12+ and run from the project root:

```sh
python3 -m venv server/.venv
server/.venv/bin/pip install -r server/requirements-dev.lock
server/.venv/bin/pip install -e server --no-deps
```

Set a private persistent directory outside the repository, create an account, and start the service:

```sh
export EUROS_DATA_DIR=/absolute/path/to/private/euros
server/.venv/bin/euros-server --create-account athlete
server/.venv/bin/euros-server --port 3013
```

The account command prompts for a password. All users, authorizations and training data live in `EUROS_DATA_DIR/euros.db`. Sign-up is closed by default. Use your server's account site at `/account/` to manage your plan; the Android app signs in through the browser with OAuth and PKCE.

The server binds to loopback. HTTPS hosting and phone enrollment are described in [backend setup](server/README.md); routes are in the [API contract](docs/API_CONTRACT.md). [.env.example](.env.example) lists configuration placeholders; the server does not load dotenv files automatically. AI configuration is optional for storage and sync.

## Build Android

Use JDK 21 and an Android SDK with API 36. Set `ANDROID_HOME` or create an untracked `android/local.properties` with your SDK location.

```sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Sign in to your own server URL from Settings; static-token enrollment remains available for configured deployments. Example hostnames are placeholders; they do not connect to the original deployment. Enable the required Health Connect permissions on your device.

## Tests

```sh
python3 -m venv server/.venv
server/.venv/bin/pip install -r server/requirements-dev.lock
server/.venv/bin/pip install -e server --no-deps
server/.venv/bin/python -m pytest server/tests
python3 -m unittest discover -s scripts -p 'test_*.py'
```

## Publication boundary

This repository begins with a fresh source snapshot. Private development history, actual tracker/food/weight records, device screenshots, credentials and host-specific deployment/security notes are excluded. Tests use fixtures; generated diagrams are illustrative. Personal configuration examples remain for customization. No hosted service, credentials or signed release APK is provided.

## Demo data

[Demo setup](docs/DEMO.md) creates a separate database with fictional activities, meal plans, food logs and weights. Seeding is explicit and refuses an existing database.

## Plans and license

See [ROADMAP.md](ROADMAP.md) for planned work. Licensed under [MIT](LICENSE).
