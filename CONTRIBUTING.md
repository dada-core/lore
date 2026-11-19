# Contributing to Lore

Thank you for your interest in Lore!

## Current stage

Every feature of v0.1 is in place, with a pass on quality (journeys in real browsers, accessibility, the five minutes of the installation timed, upgrades and restores tested), published in the 0.1.0-rc.1 release candidate before version 0.1.0. The most useful contributions right now are:

- **Feedback on the project's direction.** Open an issue to comment on the scope described in the [README](README.md), ask questions or suggest use cases.
- **Your experience as a potential user.** If you are a developer, SRE, system administrator or support engineer, tell us where your team's documentation lives today and how you search it.
- **Bug reports** on what already works, with the steps to reproduce them.

## Development setup

Requirements: Java 21, Node.js 24.15 or later (24 LTS), Docker.

```sh
docker compose -f compose.dev.yml up -d postgres   # development database, port 5433
./mvnw spring-boot:run                             # server on http://localhost:8080
cd web && npm install && npm run dev               # interface on http://localhost:5173
```

In development, the initial setup code is set in `src/main/resources/application-dev.yml`. Imported files go to `data/files`, ignored by Git: delete it together with the database to start over.

The website and the documentation have their own repository, [dada-core/lore-site](https://github.com/dada-core/lore-site) (Astro). The documentation pages are Markdown files in its `src/content/docs/` folder, in French and English: when a change alters a behaviour they describe, open a pull request there as well.

| Command | What it checks |
| --- | --- |
| `./mvnw verify` | Server tests on a real PostgreSQL (Testcontainers), and the boundaries between modules |
| `npm test` (in `web/`) | Interface tests |
| `npm run lint` and `npm run typecheck` (in `web/`) | Code rules and TypeScript types |
| `npm run api` (in `web/`) | Regenerates the API description and the interface's types after a controller change |
| `npm run e2e` (in `web/`) | The journeys of version 0.1 in Chromium, with an accessibility audit (axe) of each screen; those of reading, search, the keyboard and the phone in Firefox and WebKit too |

The journeys (`web/e2e/`) run against a fresh installation of Lore, the Docker image of the branch in the continuous integration. The first one performs the initial setup in the browser, with the code of the logs:

```sh
echo "DB_PASSWORD=$(openssl rand -base64 24)" > .env   # once
docker compose -p lore-e2e up -d --build --wait
cd web && npm ci && npx playwright install --with-deps chromium firefox webkit
LORE_SETUP_CODE=$(docker compose -p lore-e2e logs --no-log-prefix lore | grep -A2 "enter this setup code" | tail -1 | tr -d ' ') npm run e2e
```

`LORE_SETUP_CODE` is read by the journeys here, not by Lore. `LORE_E2E_URL` points them to another address than http://localhost:8080. They have their own Docker Compose project, `lore-e2e`, with its own data, on port 8080: stop another installation that uses it first. To run them again, start from an empty installation: `docker compose -p lore-e2e down --volumes`, then the first command again. `sh packaging/five-minutes.sh docker-compose.yml` times the first journey from the download of `docker-compose.yml`, as the continuous integration does, and fails above three minutes of machine time: it reuses the `lore:local` image built above and publishes port 8080, so stop the installation of the journeys first (`docker compose -p lore-e2e down --volumes`).

## Archive and Linux packages

The `packaging/` folder builds the universal archive (`.tar.gz` and `.zip`) and the `.deb` and `.rpm` packages, which bundle a Java runtime made with jlink and install Lore as a systemd service. On any machine with Docker:

```sh
docker build -f packaging/Dockerfile --output build/dist .
```

The files land in `build/dist/`. Add `--platform linux/arm64` for the arm64 packages (emulated on another architecture, so slower). On Linux, after building the interface and `./mvnw -DskipTests package`, `sh packaging/package.sh all` builds the same files for the architecture of the machine: it needs `JAVA_HOME` pointing to a JDK 21, `zip` and `unzip`, and runs nfpm from the `PATH`, or else in Docker.

| File | Role |
| --- | --- |
| `packaging/lore` and `lore.cmd` | The `lore` command of the archive and the packages |
| `packaging/lore.env` | The settings template: `/etc/lore/lore.env` in the packages |
| `packaging/lore.service` | The systemd unit, limited to directives known by systemd 239 (RHEL 8) |
| `packaging/nfpm.yaml`, `packaging/scripts/` | The package description and its installation scripts |
| `packaging/test.sh` | Installs a package in a container, starts Lore and removes it: the CI runs it on every supported release |
| `packaging/import-check.sh` | Imports a PDF and a Markdown file into a fresh installation, waits for their text, then downloads, reads and searches them and imports a version 2: the CI runs it on the image, the archive and the packages |
| `packaging/upgrade-check.sh` | After an upgrade or a restored backup, checks that what `import-check.sh` put in Lore is still there, whole |
| `packaging/five-minutes.sh` | Times the first journey from the download of `docker-compose.yml`, and fails above three minutes of machine time |

A `v*` tag matching the version in `pom.xml` publishes a release (`.github/workflows/release.yml`): the archive, the packages for amd64 and arm64, the image `ghcr.io/dada-core/lore`, the `docker-compose.yml` that names it, their checksums and the GitHub release. The image is first pushed under a `-candidate` tag. Once it has passed the five minutes, on amd64 and arm64, and the vulnerability analysis, and the packages have passed their checks, it is tagged with its version, and with its major.minor (`0.1`) for a stable version; only then is the release published.

## Database migrations

The schema is managed by [Flyway](https://documentation.red-gate.com/flyway), with plain SQL files in `src/main/resources/db/migration/`.

- **When.** Pending migrations are applied when Lore starts, before it serves any request (the container is healthy only afterwards). An upgrade is therefore: new image or package, restart. Flyway records what it applied in the `flyway_schema_history` table, and takes a PostgreSQL lock so that two instances starting together do not migrate twice.
- **Naming.** `V<number>__<what>.sql`, with two underscores, numbers in sequence: `V9__library_document_tags.sql`. Name the module whose tables the file changes. A misnamed file stops the startup instead of being skipped.
- **Never edit a migration that has been merged.** Flyway checks the checksum of every applied file and refuses to start if one changed. To fix a mistake, add a new migration.
- **Forward only.** There are no "down" migrations. Write migrations that keep the data (add a column with a default, copy, then drop in a later release). The way back for an administrator is the backup taken before the upgrade, as the upgrade guide says.
- **One module, its tables.** Each module creates and changes only its own tables; a table of another module is reached through that module's code, never by SQL.
- **PostgreSQL 18 only.** Migrations may use its features (`uuidv7()`, `jsonb`) and the extensions that ship with it (`unaccent`). pgvector is in the Docker image, but the packages do not require it: a migration cannot depend on it.
- **Tested for real.** The test suite starts a PostgreSQL 18 container and applies every migration from scratch at each run (`./mvnw verify`): a broken migration fails the build.

## Conventions

- **Modules.** The server is one application split into modules, one per package (`identity`, `audit`, `web`...). A module never reads another module's tables: a design rule, which no test checks. `ModularityTests` (Spring Modulith) checks that each module only uses the public types of the others, without cycles between modules.
- **Errors.** The API never sends interface text: errors carry a stable `code` (and field errors), which the interface translates.
- **Languages.** The interface is in French and English: every text lives in `web/src/i18n/messages.ts`, in both languages.
- **Addresses.** Every route, API path and URL parameter is in English, whatever the language of the page.
- **Typography.** No em dash nor en dash anywhere in the repository: code, comments, interface text, scripts and documentation. `npm run lint` checks every file.
- **Tests.** Each user-facing behaviour comes with a test: an API test for the server, a Testing Library test for the interface.

## Developer Certificate of Origin

Lore asks for no contributor agreement. Each commit is signed off under the [Developer Certificate of Origin](https://developercertificate.org/): by adding a `Signed-off-by` line with your name and email address, you certify that you wrote the change, or have the right to submit it under the project's license.

```sh
git commit -s -m "Explain what the change does"
```

You keep the copyright on your contribution, which is published under the AGPL-3.0, like the rest of Lore. A pull request whose commits are not signed off cannot be merged: `git commit --amend -s`, or `git rebase --signoff main` for several commits, adds the line afterwards.

## Ground rules

- Be respectful: this project follows its [Code of Conduct](CODE_OF_CONDUCT.md).
- Never report security vulnerabilities in public issues: follow [SECURITY.md](SECURITY.md).
- For significant changes, open an issue to discuss them before writing code.
