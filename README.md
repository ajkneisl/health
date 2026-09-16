# health

A small health-checking dashboard. It polls a configured list of endpoints on a schedule, keeps the
results in memory, and renders them server-side with the Kotlin HTML DSL (kotlinx.html) on top of Ktor.

## What it shows

Deliberately plain: one table per group, no colours, almost no CSS.

One row per endpoint: current status, `version`, HTTP code, latency, rolling uptime, and time of the
last check, with the recent checks as one character each (`.` up, `~` degraded, `x` down).

Clicking the name expands a detail row underneath it holding the url, the error (if any), the `stats`
the endpoint reported (one per line), and the check counters. Rows stay open across updates. Without
JavaScript the detail rows are simply always visible.

Updates never reload the page — a small script swaps the content element in place on a timer and
immediately (over a websocket) whenever an endpoint changes status, so nothing flashes and the scroll
position is kept. Without JavaScript the page still renders, and "Check now" falls back to a plain
form post.

## Endpoint contract

Endpoints come in two types, set per endpoint (or in `defaults`) with `"type"`:

| Type              | Behaviour                                                                      |
|-------------------|--------------------------------------------------------------------------------|
| `full` (default)  | Reads the JSON body for `status`, `version` and `stats`, and flags slow responses as degraded |
| `simple`          | Up when the response is a 200, down otherwise. The body is never read           |

A `full` endpoint can return anything — a plain `200` is enough to be reported as up. If the response
body is JSON, three optional top-level fields are picked up:

```json
{
  "status": "ok",
  "version": "2.7.1",
  "stats": {
    "queueDepth": 12,
    "memory": { "usedMb": 412, "maxMb": 1024 },
    "regions": ["us", "eu"]
  }
}
```

| Field     | Effect                                                                                       |
|-----------|----------------------------------------------------------------------------------------------|
| `status`  | `degraded`/`warn`/`warning`/`partial` renders as degraded; `down`/`error`/`unhealthy`/`fail`/`failed` as down |
| `version` | Shown as a chip on the card; a change within the retained window is noted as "upgraded from …" |
| `stats`   | Any JSON object. Nested objects are flattened into `Memory › Used mb`, arrays of primitives are joined |

Everything in `stats` is optional and free-form — whatever an endpoint reports gets a tile.

## Configuration

`health.json` in the working directory (or `$HEALTH_CONFIG`, or `health.json` on the classpath):

```json
{
  "title": "Health",
  "refreshSeconds": 15,
  "defaults": {
    "intervalSeconds": 15,
    "timeoutMillis": 5000,
    "historySize": 40,
    "degradedAfterMillis": 250,
    "expectedStatuses": [200],
    "statsPath": "stats"
  },
  "endpoints": [
    { "name": "Public API", "url": "https://example.com/health", "group": "Core" }
  ]
}
```

Each endpoint takes `name` and `url`, and may override any value from `defaults` plus `type`,
`method`, `group`, and `headers`. `statsPath` is a dotted path to the stats object (`"data.stats"`), or `""` to
treat the whole body as stats.

The checked-in config points at the built-in `/demo/*` endpoints so the dashboard is populated on a
fresh clone; replace those entries with real ones and delete `src/main/kotlin/Demo.kt`.

## Email alerts (Amazon SES)

Mail goes out once an endpoint has been down or degraded for `alertAfterChecks` checks in a row, and once a day with a report covering
every endpoint. Credentials are never read from `health.json` — the AWS default provider chain is
used (`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`, `AWS_PROFILE`, or the instance/container role) —
and `from` must be an address or domain verified in SES.

```json
"email": {
  "enabled": true,
  "region": "us-east-1",
  "from": "health@example.com",
  "to": ["ops@example.com"],
  "subjectPrefix": "[health]",
  "cooldownMinutes": 15,
  "alertAfterChecks": 5,
  "notifyOnRecovery": false,
  "dailyReport": { "enabled": true, "at": "08:00", "zone": "America/Chicago" }
}
```

| Setting             | Effect                                                                      |
|---------------------|------------------------------------------------------------------------------|
| `cooldownMinutes`   | Drops a repeat alert for the same endpoint and status inside the window, so a flapping endpoint cannot flood the recipients |
| `alertAfterChecks`  | Down or degraded checks in a row before mailing (default 5), so a blip shorter than that is never mailed about |
| `notifyOnRecovery`  | Also mail when an endpoint comes back from an outage that was mailed about   |
| `dailyReport.at`    | Local time of day, `HH:mm`; `zone` is an IANA id, empty for the server's zone |

Alerts fire when the streak reaches the threshold, not on every failing check — an endpoint that
stays down is mailed about once (again if it moves between down and degraded, and again only after
the cooldown for a later outage).

While `enabled` is false, or `from`/`to` are unset, nothing is sent: every message that would have
gone out is logged instead (`Not sending "[health] …" — email is disabled`), which is a cheap way to
watch the rules before pointing them at SES. `POST /report` sends the daily report immediately, to
check the SES setup without waiting for the schedule.

## Routes

| Route         | Description                                                     |
|---------------|-----------------------------------------------------------------|
| `GET /`       | The dashboard                                                   |
| `GET /fragment` | The content element on its own, used for the in-place updates |
| `GET /api/health` | The same snapshot as JSON                                   |
| `POST /check` | Re-checks every endpoint now (the "Check now" button)           |
| `GET /ws/health` | Websocket that ticks on every status change                  |
| `POST /report`| Sends the daily status report now                               |
| `GET /healthz`| The dashboard's own health, in the format described above       |
| `GET /demo/*` | Sample endpoints used by the default config                     |

## Source layout

Everything lives under `dev.ajkneisl.health`, one class per file.

| Package    | Contents                                                                                 |
|------------|-------------------------------------------------------------------------------------------|
| (root)     | `Main.kt` (entry point) and `Application.kt` (module wiring)                               |
| `config`   | `HealthConfig`, `EndpointConfig`/`EndpointDefaults`/`Endpoint`, `CheckType`, `EmailConfig`, `DailyReportConfig`, `ConfigLoader` |
| `monitor`  | `HealthMonitor` and the scheduling, `EndpointState`, `Status`, `CheckResult`, `EndpointSnapshot`, `DashboardSnapshot`, `StatusChangeListener`, `Stat` and `Stats.kt` |
| `mail`     | `Alerter` (rules, cooldown, daily schedule), `Emails` (bodies), `Mailer`/`SesMailer`/`LoggingMailer`, `EmailMessage` |
| `web`      | `Routing.kt`, `Dashboard.kt` and `DashboardAssets.kt` (CSS and script), `Demo.kt` (sample endpoints, safe to delete) |
| `plugins`  | The Ktor plugin installers from the project generator                                      |

Tests mirror the packages: `monitor/HealthMonitorTest`, `mail/AlerterTest`, `web/DashboardRoutesTest`.

The generated `configureSecurity()` (sample JWT) and `configureRequestValidation()` are not installed
by `healthModule`; add them back in `Application.kt` if you want the dashboard behind auth.

## Building & Running

| Task              | Description       |
|-------------------|-------------------|
| `./gradlew test`  | Run the tests     |
| `./gradlew build` | Build the project |
| `./gradlew run`   | Run the server    |

The server listens on <http://localhost:8080>; set `PORT` to use another.
