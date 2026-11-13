# Contributing to Lore

Thank you for your interest in Lore!

## Current stage

The first part of v0.1 is in place: accounts, sign-in and the administration of the organization. The most useful contributions right now are:

- **Feedback on the project's direction.** Open an issue to comment on the scope described in the [README](README.md), ask questions or suggest use cases.
- **Your experience as a potential user.** If you are a developer, SRE, system administrator or support engineer, tell us where your team's documentation lives today and how you search it.
- **Bug reports** on what already works, with the steps to reproduce them.

## Development setup

Requirements: Java 21, Node.js 22.12 or later, Docker.

```sh
docker compose -f compose.dev.yml up -d postgres   # development database, port 5433
./mvnw spring-boot:run                             # server on http://localhost:8080
cd web && npm install && npm run dev               # interface on http://localhost:5173
```

In development, the initial setup code is set in `src/main/resources/application-dev.yml`.

The website and the documentation have their own repository, [dada-core/lore-site](https://github.com/dada-core/lore-site) (Astro). The documentation pages are Markdown files in its `src/content/docs/` folder, in French and English: when a change alters a behaviour they describe, open a pull request there as well.

| Command | What it checks |
| --- | --- |
| `./mvnw verify` | Server tests on a real PostgreSQL (Testcontainers), and the boundaries between modules |
| `npm test` (in `web/`) | Interface tests |
| `npm run lint` and `npm run typecheck` (in `web/`) | Code rules and TypeScript types |
| `npm run api` (in `web/`) | Regenerates the API description and the interface's types after a controller change |

## Archive and Linux packages

The `packaging/` folder builds the universal archive (`.tar.gz` and `.zip`) and the `.deb` and `.rpm` packages, which bundle a Java runtime made with jlink and install Lore as a systemd service. On any machine with Docker:

```sh
docker build -f packaging/Dockerfile --output build/dist .
```

The files land in `build/dist/`. Add `--platform linux/arm64` for the arm64 packages (emulated on another architecture, so slower). On Linux, with a JDK 21 and after building the interface and `./mvnw -DskipTests package`, `sh packaging/package.sh all` does the same without Docker.

| File | Role |
| --- | --- |
| `packaging/lore` and `lore.cmd` | The `lore` command of the archive and the packages |
| `packaging/lore.env` | The settings template: `/etc/lore/lore.env` in the packages |
| `packaging/lore.service` | The systemd unit, limited to directives known by systemd 239 (RHEL 8) |
| `packaging/nfpm.yaml`, `packaging/scripts/` | The package description and its installation scripts |
| `packaging/test.sh` | Installs a package in a container, starts Lore and removes it: the CI runs it on every supported release |

A `v*` tag matching the version in `pom.xml` publishes a release (`.github/workflows/release.yml`): the archive, the packages for amd64 and arm64, the image `ghcr.io/dada-core/lore`, their checksums and the GitHub release.

## Database migrations

The schema is managed by [Flyway](https://documentation.red-gate.com/flyway), with plain SQL files in `src/main/resources/db/migration/`.

- **When.** Pending migrations are applied when Lore starts, before it serves any request (the container is healthy only afterwards). An upgrade is therefore: new image or package, restart. Flyway records what it applied in the `flyway_schema_history` table, and takes a PostgreSQL lock so that two instances starting together do not migrate twice.
- **Naming.** `V<number>__<what>.sql`, with two underscores, numbers in sequence: `V4__identity_display_settings.sql`. Name the module whose tables the file changes. A misnamed file stops the startup instead of being skipped.
- **Never edit a migration that has been merged.** Flyway checks the checksum of every applied file and refuses to start if one changed. To fix a mistake, add a new migration.
- **Forward only.** There are no "down" migrations. Write migrations that keep the data (add a column with a default, copy, then drop in a later release). The way back for an administrator is the backup taken before the upgrade, as the upgrade guide says.
- **One module, its tables.** Each module creates and changes only its own tables; a table of another module is reached through that module's code, never by SQL.
- **PostgreSQL 18 only.** Lore runs on PostgreSQL (with pgvector), so migrations may use its features (`uuidv7()`, `jsonb`, extensions).
- **Tested for real.** The test suite starts a PostgreSQL 18 container and applies every migration from scratch at each run (`./mvnw verify`): a broken migration fails the build.

## Conventions

- **Modules.** The server is one application split into modules, one per package (`identity`, `audit`, `web`...). A module never reads another module's tables; `ModularityTests` checks the boundaries.
- **Errors.** The API never sends interface text: errors carry a stable `code` (and field errors), which the interface translates.
- **Languages.** The interface is in French and English: every text lives in `web/src/i18n/messages.ts`, in both languages.
- **Addresses.** Every route, API path and URL parameter is in English, whatever the language of the page.
- **Typography.** No em dash anywhere in the code base: comments, strings and interface text. `npm run lint` checks the interface.
- **Tests.** Each user-facing behaviour comes with a test: an API test for the server, a Testing Library test for the interface.

## Contributor License Agreement

Before we can merge your first contribution, you will be asked to sign a Contributor License Agreement (CLA).

You keep the copyright on your contribution. The CLA grants the project a license to use and distribute it, including under licenses other than the AGPL-3.0. This is what allows Lore to offer paid editions alongside the open source Community edition, which funds the project.

The CLA will be based on the Apache Software Foundation's Individual Contributor License Agreement. Its text will be published in this repository before the first external contribution is accepted.

## Ground rules

- Be respectful: this project follows its [Code of Conduct](CODE_OF_CONDUCT.md).
- Never report security vulnerabilities in public issues: follow [SECURITY.md](SECURITY.md).
- For significant changes, open an issue to discuss them before writing code.
