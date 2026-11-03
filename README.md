# la-isla-bonita
Ever wanted Tropico without graphics? I am creating one

A population simulation of an island from 1900 to 2025.

- `server/` — Ktor HTTP server, state stored in SQLite (`game.db`)
- `client/` — interactive CLI that talks to the server
- `shared/` — DTOs used by both
- `server/src/main/resources/migrations/` — schema migrations (`V###__name.sql`, applied in order on startup)

## Running

Requires JDK 21 and Gradle (the Gradle toolchain resolver will download JDK 21 if missing).

```sh
gradle :server:run                 # http://localhost:8080, creates ./game.db
gradle -q --console=plain :client:run
```

Or build a standalone client: `gradle :client:installDist && client/build/install/client/bin/client [server-url]`.

Server config lives in `server/src/main/resources/application.conf`; each setting can be overridden by an
environment variable:

| Setting | Env var | Default |
|---|---|---|
| `isla.host` | `HOST` | `127.0.0.1` (this machine only; use `0.0.0.0` to expose on the network) |
| `isla.port` | `PORT` | `8080` |
| `isla.db` | `ISLA_DB` | `game.db` |

The client takes the server URL as its first argument or `ISLA_SERVER` (default `http://localhost:8080`). Use the `reset` command to start over from 1900 with a fresh population.

## Deploying to Kubernetes

Pushing to `master` builds `ghcr.io/areso/la-isla-bonita` (linux/amd64) via
`.github/workflows/image.yml`, tagged `latest` and `sha-<commit>`. The image
runs the server on `0.0.0.0:8080` with the database at `/data/game.db`, and
also contains the CLI client as `isla`.

The Helm chart in `chart/` deploys one replica (SQLite allows a single writer)
with the database on a PersistentVolumeClaim (`do-block-storage` by default):

```sh
helm upgrade --install isla chart -n default

# Play from inside the pod
kubectl exec -it deploy/isla-la-isla-bonita -- isla

# Or from your machine
kubectl port-forward svc/isla-la-isla-bonita 8080:8080
ISLA_SERVER=http://localhost:8080 client/build/install/client/bin/client
```

| Value | Default | |
|---|---|---|
| `image.repository` | `ghcr.io/areso/la-isla-bonita` | |
| `image.tag` | chart `appVersion` (`latest`) | pin to `sha-<commit>` for reproducible deploys |
| `imagePullSecrets` | `[]` | needed while the ghcr.io package is private |
| `persistence.enabled` | `true` | `false` = emptyDir, data lost on restart |
| `persistence.storageClass` | `do-block-storage` | |
| `persistence.size` | `1Gi` | |
| `persistence.keepOnUninstall` | `true` | `helm uninstall` leaves the volume (and the game) in place |
| `resources` | 100m CPU / 256Mi, limit 512Mi | |

## Rules

- `init` creates 5000 people on January 1 of the starting year, with a realistic age pyramid;
  about 65% of adult women start out married.
- Every year, based on the population on January 1:
  - **deaths**: 12 per 1000, older people far more likely to die;
  - **marriages**: 8 per 1000, between single or widowed people aged 18–55;
  - **births**: 20 per 1000, to married women aged 16–45 whose husband is alive. Children take the father's surname.

## API

| Method | Path | |
|---|---|---|
| GET | `/world` | status |
| POST | `/world/init` | populate the island |
| POST | `/world/reset` | wipe people and history, repopulate at the starting year |
| POST | `/world/advance?years=N` | simulate N years |
| GET | `/history` | per-year births/deaths/marriages |
| GET | `/people?alive=true\|false\|all&name=&offset=&limit=` | list people |
| GET | `/people/{id}` | person with parents, spouse, children |

## TODOs, IDEAs, and so on
- round 1. More people are born (60) and only 40 marriages were done. Either marry some people when populate, or limit number of births to number of married people
- maybe some TUI?
- add taxes and expendatures
- as game progresses, add more information to people: say, blood type, vaccinations and so on.