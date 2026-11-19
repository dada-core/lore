# Lore

**Technical Knowledge Workspace**

Lore is an open source workspace where technical teams import, organize, search and maintain their technical knowledge: documentation, architecture documents, runbooks, procedures and incident reports. Approving documents (v0.2) and writing them in Lore (later) are planned.

> **Lore is not a chatbot.** It is a complete workspace. An optional assistant, planned for v0.3, will answer questions in natural language, but it will be one way in among others, and the platform is fully usable without it.

## Status

Under development, towards v0.1. Working today: the initial setup in the browser, sign-in, invitations, password reset links, My account, and the administration of the organization (members, roles, security settings); spaces with their folders and roles (reader, contributor, maintainer); the import of PDF, Word, Markdown and text files, whose text is extracted in a separate process, with documents restricted to a few people; the reader: Markdown, Word and text laid out and cleaned, PDF files read in the page, a link that opens a document at a section or a page, the download of the original, and the person in charge of each document; and full-text search in the documents a person may read, with filters, a palette (Ctrl K) and results that open at the passage found; the versions of each document, a new one becoming current once its text is extracted, with the history in strata; statuses (draft, published, outdated with the document that replaces it, archived); the activity of each document; and the audit log of the organization, with its CSV export. The scope of the first releases is described under [Planned releases](#planned-releases).

A release candidate, [0.1.0-rc.2](https://github.com/dada-core/lore/releases/tag/v0.1.0-rc.2), holds every feature of version 0.1 and its pass on quality. Its data will carry over to version 0.1.0.

## Installation

From version 0.1, Lore installs on your own servers in three ways, with PostgreSQL 18 as its only dependency:

- **Docker Compose**: the image `ghcr.io/dada-core/lore`, for amd64 and arm64.
- **.deb and .rpm packages**: Ubuntu 22.04, 24.04 and 26.04 LTS, Debian 12 and 13, RHEL, Rocky Linux and AlmaLinux 8, 9 and 10, for amd64 and arm64. Lore runs as a systemd service, with its own Java runtime.
- **Universal archive**: any system with Java 21 or later, Windows and macOS included.

Each release publishes these files on the [releases page](https://github.com/dada-core/lore/releases). See the [installation guide](https://lore.dada-core.com/en/docs/installation/).

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

Only what is marked v0.1 exists today; the rest is planned.

| Area | Purpose | Release |
|---|---|---|
| Library | Spaces, folders and documents: import, preview, versions | v0.1 (writing in Lore: later) |
| Search | Full-text search, filters, excerpts | v0.1 (semantic search: v0.2) |
| Knowledge base | Problem / solution records and validated lessons learned | v0.2 |
| Governance | Statuses, owners, permissions, audit logs | v0.1 (reviews: v0.2) |
| Connected sources | Connectors to the tools teams already use | Later |
| Assistant (optional) | Natural-language questions with sourced answers | v0.3 |
| Insights | Usage, outdated documents, documentation gaps | v0.2 |

## Principles

- **Permissions first.** No one ever sees content from a document they cannot access: not in search results, not in excerpts, and not through the assistant planned for v0.3.
- **Sourced and verifiable.** Every result links back to the document, version and passage it comes from.
- **Works without AI.** The assistant, planned for v0.3, will be an optional module that organizations can turn off.
- **Easy to self-host.** Lore and PostgreSQL, nothing else: a Docker image, a .deb or .rpm package, or an archive. Goal: up and running in under five minutes.

## Planned releases

| Version | Scope |
|---|---|
| v0.1 | Accounts, the organization, spaces, permissions, PDF/DOCX/TXT/Markdown import, document viewer, permission-aware full-text search, basic versioning |
| v0.2 | Semantic search, knowledge base records, dashboard, review dates and approval workflow |
| v0.3 | Optional assistant, conversation history |

## License

Lore is open source, licensed under the [GNU Affero General Public License v3.0](LICENSE). Every feature is free: there is no paid edition.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Please read our [Code of Conduct](CODE_OF_CONDUCT.md), and report security issues as described in [SECURITY.md](SECURITY.md).
