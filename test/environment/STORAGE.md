# Real storage environment probes

`StorageEnvironmentProbe` calls production `DatabaseMigration`, `MySQLProvider`,
`PostgreSQLProvider`, `RedisProvider` and the public invitation APIs. It does not
use SQLite, MemoryProvider, mocked JDBC, JUnit or game servers.

For SQL, an empty dedicated database is mandatory. The probe runs production
migrations capped at v15, inserts an unbound invitation and a message, snapshots
all migration metadata, and lets the current provider upgrade to v16. It compares
all 15 old checksums and metadata rows, the invitation and message, and checks
that v16 completed and `target_player_id` exists. The fixture is built from the
production v1–v15 statements; it is not a restored historical database backup.

For every provider it persists a bound invitation, rejects a wrong recipient
without consuming it, reopens the provider, races eight correct-recipient claims,
requires exactly one success, rejects replay, and reopens again to verify the
consumed state. An old unbound invitation must still accept an arbitrary player
once. Redis additionally inspects the JSON on the real server and loads an old
DTO with no `targetPlayerId`. Redis does not run SQL migrations.

## Recorded native results (2026-11-02)

All **41/41 real-provider assertions passed**: Oracle MySQL 11 + MariaDB 11 +
PostgreSQL 11 + Redis 8. These counts are assertion executions across the four
real database servers, using the production providers.

| Database | Passing assertions | Per-engine artifact |
| --- | --- | --- |
| Oracle MySQL 8.4.13 LTS via MySQLProvider | 11/11 | [mysql.storage-probe.json](../../.e2e-artifacts/environment-native/8c7ef98ec0224683bba9d5e7a94cd878/public/mysql.storage-probe.json) |
| MariaDB 12.3.3 via MySQLProvider | 11/11 | [mysql.storage-probe.json](../../.e2e-artifacts/environment-native/19bcd51611aa48d1bed944bb6156d3bc/public/mysql.storage-probe.json) |
| PostgreSQL 18.6 | 11/11 | [postgresql.storage-probe.json](../../.e2e-artifacts/environment-native/19bcd51611aa48d1bed944bb6156d3bc/public/postgresql.storage-probe.json) |
| Redis 8.10.1 | 8/8 | [redis.storage-probe.json](../../.e2e-artifacts/environment-native/19bcd51611aa48d1bed944bb6156d3bc/public/redis.storage-probe.json) |

The [original native summary](../../.e2e-artifacts/environment-native/19bcd51611aa48d1bed944bb6156d3bc/public/native-storage-summary.json)
retains the 30 MariaDB/PostgreSQL/Redis assertions. The separate
[Oracle native summary](../../.e2e-artifacts/environment-native/8c7ef98ec0224683bba9d5e7a94cd878/public/native-storage-summary.json)
adds 11 assertions without rerunning or replacing those earlier results.
Official Oracle ZIP provenance, MD5, SHA-256, Authenticode status and the exact
command are retained in the
[source manifest](../../.e2e-artifacts/environment-native/8c7ef98ec0224683bba9d5e7a94cd878/public/oracle-source-manifest.json).

These native runs do not exercise TLS or actual Bukkit/Fabric game traffic.
The later CI game paths are recorded below. Redis reconnect evidence covers new
provider pools against the same live server; it does not establish disk recovery
after a Redis restart or crash.

## Native Windows run

The existing Scoop installations are used directly. Neither Docker nor PowerShell
7 is required for this path. Build output is isolated from other Gradle work:

```powershell
$env:JAVA_HOME = "$env:USERPROFILE/scoop/apps/temurin21-jdk/current"
.\gradlew.bat :StarLink:storage:environmentStorageProbeClasspath `
  --init-script test/bin/environment-storage.init.gradle `
  '-PenvironmentBuildRoot=.e2e-artifacts/environment-probe-schema16' `
  --project-cache-dir .e2e-artifacts/environment-probe-schema16/gradle-cache `
  -x test --offline --no-daemon --configure-on-demand --console=plain
.\test\environment\run-native-storage.ps1 `
  -ProbeClassPathFile .e2e-artifacts/environment-probe-schema16/storage/environment-probe.classpath.txt
```

`-Engines redis` selects one engine. `-MySqlBin`, `-PostgresBin`, `-RedisBin` and
`-Jdk` accept existing installation directories. By default `mysqld.exe` comes
from Scoop's MariaDB package; evidence records the actual product and version.
A MariaDB success is not evidence that Oracle MySQL 8.4 ran locally. An existing
Oracle MySQL installation can be selected with `-MySqlBin`.

Oracle initialization and startup explicitly select the portable base directory,
use a 64 MiB InnoDB buffer pool and 32 MiB redo capacity, disable Performance
Schema and MySQL X, and limit connections to 16. Use a separate Gradle project
cache as well as a separate build output when another build runs concurrently.
The native runner checks that both probe and production migration classes are
present before starting any database.

Oracle MySQL **8.4.13 LTS** was separately verified on 2026-11-02 with
`-Engines mysql`, using the regular Windows ZIP from the
[official release page](https://dev.mysql.com/downloads/mysql/8.4.html?os=3).
The 281,154,912-byte archive came through the official redirect to
`https://cdn.mysql.com//Downloads/MySQL-8.4/mysql-8.4.13-winx64.zip`.
Its MD5 matched the official `7b94489d5524dea9b27097068bea25a8`; its SHA-256 is
`e9cd98b1d23f122987b1afca26ad942efec5891481103d48ae3cde237fa3ea54`.
`mysqld.exe` has a valid Authenticode signature from Oracle America, Inc.

All 11 MySQL provider assertions passed on loopback port **57009**. The six
owned processes exited and temporary database files were removed. The portable
ZIP and binaries remain in their dedicated C: temporary directory for reuse;
no system service was installed. Earlier MariaDB, PostgreSQL and Redis evidence
is retained unchanged. TLS and actual game paths were not run by this check.
Local evidence and exact execution command are recorded in
[the Oracle summary](../../.e2e-artifacts/environment-native/8c7ef98ec0224683bba9d5e7a94cd878/public/native-storage-summary.json)
and [source manifest](../../.e2e-artifacts/environment-native/8c7ef98ec0224683bba9d5e7a94cd878/public/oracle-source-manifest.json).

The first Oracle attempt stopped before any provider assertion because the old
classpath referenced a missing production classes directory. Rebuilding with
separate output and a separate Gradle project cache restored the probe; only
the MySQL engine was rerun. No production provider change was needed.

Each run creates a GUID directory under `.e2e-artifacts/environment-native/`,
initializes fresh database files and chooses unused nondefault loopback ports.
It passes `--no-defaults` to MySQL and explicit data directories to PostgreSQL
and Redis. It never registers a service or uses a default database. Only the
processes started by this run are stopped. Database files are removed after
checking the absolute path is below the task directory and all owned processes
have exited; `-KeepDatabaseFiles` preserves them. Logs and JSON evidence remain.
SQL plaintext is explicitly opted in on this isolated native path; it does not
provide TLS or actual game delivery evidence. Redis reconnect evidence concerns
provider/pool recreation against the same live server, not a Redis server crash
or disk durability guarantee.

## Manual game workflow

### CI run at schema 16 (2026-11-04)

The manual run [41852328574](https://github.com/XingLingQAQ/NovaLink/actions/runs/41852328574)
checked out `93fd79f7acddfb297d73448170533767e87dbf90` and selected only
`mysql,postgresql,redis`. The workflow failed overall. Its public artifact is
`environment-game-evidence-41852328574-1` (retained for seven days); its
downloaded contents are under `.e2e-artifacts/environment-ci/41852328574/`.

| Engine | Result | Evidence |
| --- | --- | --- |
| Oracle MySQL 8.4.13 | 16/16 required IDs passed | v15-to-v16 migration, actual game delivery and the same seven message fields across a backend restart |
| PostgreSQL 17.11 | 16/16 required IDs passed | v15-to-v16 migration, actual game delivery and the same seven message fields across a backend restart |
| Redis 7.4.12 | 8/13 required IDs passed | All provider assertions passed; backend startup failed before the five game assertions because Redis lacked campaign persistence |

Each case's `public/environment-summary.json` preserves the IDs and their
verification type; `public/storage-probe.json` records provider and server
identity. SQL `public/row-before.json`, `public/row-after.json` and bot
recordings supply the row and game evidence. The SQL fixture is generated from
production migrations, not a historical database backup.

In the two passing SQL cases, Bukkit/Fabric plugins connected to the backend
with TLS and a trusted CA; the history client used HTTPS with CA and hostname
verification. Redis did not reach those connections.
The SQL backend and probe connections used MySQL `REQUIRED` or PostgreSQL
`require`, with server-side TLS required: these modes encrypt but do not verify
the database server's certificate identity. Redis connections were plaintext on
loopback with a password. Game bots connected to the game servers on loopback;
this is not a TLS assertion for the Minecraft connections. Only the NovaLink
backend restarted; database servers stayed running, and Redis was configured
without disk persistence.

### Redis-only rerun after campaign persistence fix (2026-11-04)

[Run 41855733387](https://github.com/XingLingQAQ/NovaLink/actions/runs/41855733387)
checked out `19b045b81a0a1effcfd4b57f30f6a947cca63c41` and selected only
`redis`. Redis 7.4.12 passed **14/14 required IDs**: nine
`real-database-provider` assertions, including campaign persistence, and five
`real-game` assertions. This rerun follows, rather than replaces, the Redis
startup failure recorded above.

The public artifact `environment-game-evidence-41855733387-1` is retained for
seven days; its downloaded contents are under
`.e2e-artifacts/environment-ci/41855733387/20261104-151849-f69f51e4-redis/public/`.
The bot recordings show both directions before and after backend restart; both
clients authenticated in each backend log. The two row JSON files match on
`id`, `channelId`, `senderId`, `senderName`, `clientId`, `content` and
`timestamp` (message ID 1).

The Bukkit/Fabric-to-backend connections used TLS with the trusted CA, and the
history client used HTTPS with CA and hostname verification. Redis used
password-authenticated plaintext on loopback; the database server stayed
running with disk persistence disabled. This establishes backend restart and
live-Redis retention, not Redis server restart or disk recovery. Minecraft bot
connections to the game servers are not part of the TLS evidence.

`.github/workflows/e2e-environment.yml` is manual only. Its `cases` input accepts
`tls-trusted,tls-untrusted,mysql,postgresql,redis` (all five by default).
`-ProvisionDatabases` creates dedicated MySQL 8.4, PostgreSQL 17 and Redis 7.4
containers on random loopback ports. SQL still requires database TLS. Backend,
Bukkit and Fabric still use the existing certificate tests and real game bots.

The workflow builds `:StarLink:storage:environmentStorageProbeClasspath` with
`--init-script test/bin/environment-storage.init.gradle` alongside the existing
game artifacts. The default probe classpath is
`.e2e-artifacts/environment-probe-build/storage/environment-probe.classpath.txt`.
Pass `-StorageProbeClassPathFile` to `run-environment.ps1` for a different build.
The runner never compiles the probe itself.

For existing dedicated services, omit `-ProvisionDatabases` and set:

| Case | Environment variables |
| --- | --- |
| `mysql` | `NOVALINK_ENV_MYSQL_HOST`, `_PORT`, `_DATABASE`, `_USERNAME`, `_PASSWORD` |
| `postgresql` | `NOVALINK_ENV_POSTGRES_HOST`, `_PORT`, `_DATABASE`, `_USERNAME`, `_PASSWORD` |
| `redis` | `NOVALINK_ENV_REDIS_HOST`, `_PORT`, `_PASSWORD` (password may be empty) |

Each SQL database must be empty before the probe runs. Redis must be a dedicated
server: the game backend currently selects database 0. No `FLUSHDB` is used.

`cases.psd1` requires the provider assertion IDs plus five actual game assertions
per storage engine: `GAME-DELIVERY`, `STORED`, `RESTARTED`, `SAME-ROW` and
`POST-RESTART-GAME`. SQL provider IDs include `V15-FIXTURE`, `SCHEMA16`,
`OLD-CHECKSUMS`, `OLD-DATA` and `LEGACY-LOAD`; Redis includes `LEGACY-DTO` and
`TARGET-DTO`. All require `TARGET-PERSISTED`, `WRONG-TARGET`, `RECONNECT`,
`ATOMIC-CLAIM`, `UNBOUND-COMPAT` and `CLAIM-PERSISTED`, with the engine prefix
`ENV-MYSQL`, `ENV-POSTGRES` or `ENV-REDIS`.
Redis also requires `ENV-REDIS-CAMPAIGN-PERSISTENCE`, covering campaign CRUD,
field round-tripping, status updates and provider reconnects on real Redis.

Missing evidence fails the case. Provider assertions retain
`verification=real-database-provider`; actual game assertions retain
`verification=real-game`. `storage-probe.json` includes server identity and SQL
migration snapshots. `row-before.json` and `row-after.json` retain the actual game
message across backend restart. Only `**/public/**` is uploaded; credentials,
private keys and database files remain private. `-PlanOnly` proves none of these
runtime assertions.
