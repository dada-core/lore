# Lore

**Technical Knowledge Workspace**

Lore is an open source workspace where technical teams write, import, organize, search, validate and maintain their technical knowledge: documentation, architecture documents, runbooks, procedures and incident reports.

> **Lore is not a chatbot.** It is a complete workspace. An optional assistant can answer questions in natural language, but it is one way in among others, and the platform is fully usable without it.

## Status

Under development, towards v0.1. Working today: the initial setup in the browser, sign-in, invitations, password reset links, My account, and the administration of the organization (members, roles, security settings). Spaces, import, the document viewer and search come next. The scope of the first releases is described under [Planned releases](#planned-releases).

## Installation

From version 0.1, Lore installs on your own servers in three ways, with PostgreSQL 18 as its only dependency:

- **Docker Compose**: the image `ghcr.io/dada-core/lore`, for amd64 and arm64.
- **.deb and .rpm packages**: Ubuntu 22.04, 24.04 and 26.04 LTS, Debian 12 and 13, RHEL, Rocky Linux and AlmaLinux 8, 9 and 10, for amd64 and arm64. Lore runs as a systemd service, with its own Java runtime.
- **Universal archive**: any system with Java 21 or later, Windows and macOS included.

Each release publishes these files on the [releases page](https://github.com/dada-core/lore/releases). See the [installation guide](https://lore.dada-core.com/docs/installation/).

## Try it from source

You need Docker with Docker Compose.

```sh
echo "DB_PASSWORD=$(openssl rand -base64 24)" > .env
docker compose up -d --build
docker compose logs lore    # shows the initial setup code
```

Then open http://localhost:8080. Lore has no default account or password: the first administrator is created in the browser, with the setup code printed in the logs.

To work on the code, see [CONTRIBUTING.md](CONTRIBUTING.md).

## What Lore brings together

| Area | Purpose |
|---|---|
| Library | Spaces, folders and documents: import, writing, preview, versions |
| Search | Full-text and semantic search, filters, excerpts |
| Knowledge base | Problem / solution records and validated lessons learned |
| Governance | Statuses, reviews, owners, permissions, audit logs |
| Connected sources | Connectors to the tools teams already use |
| Assistant (optional) | Natural-language questions with sourced answers |
| Insights | Usage, outdated documents, documentation gaps |

## Principles

- **Permissions first.** No one ever sees content from a document they cannot access: not in search results, not in excerpts, not through the assistant.
- **Sourced and verifiable.** Every result links back to the document, version and passage it comes from.
- **Works without AI.** The assistant is an optional module that organizations can turn off.
- **Easy to self-host.** Lore and PostgreSQL, nothing else: a Docker image, a .deb or .rpm package, or an archive. Goal: up and running in under five minutes.

## Planned releases

| Version | Scope |
|---|---|
| v0.1 | Accounts, organizations, spaces, permissions, PDF/DOCX/TXT/Markdown import, document viewer, permission-aware full-text search, basic versioning |
| v0.2 | Semantic search, knowledge base records, dashboard |
| v0.3 | Optional assistant, conversation history |

## License

Lore is open core. The Community edition is licensed under the [GNU Affero General Public License v3.0](LICENSE). Features of the paid editions will live in a separate `ee/` directory under a commercial license.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Please read our [Code of Conduct](CODE_OF_CONDUCT.md), and report security issues as described in [SECURITY.md](SECURITY.md).
