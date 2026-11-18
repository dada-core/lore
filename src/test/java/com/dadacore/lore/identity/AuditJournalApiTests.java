package com.dadacore.lore.identity;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;

import tools.jackson.databind.JsonNode;

/** Administration › Audit log: the events, their filters, their pages, and their export in CSV. */
class AuditJournalApiTests extends ApiTest {

    private static final String HERVE = "herve.coulibaly@dada.group";

    @Test
    void theAdministratorsReadTheEventsByCategoryPersonAndPeriodPageByPage() {
        Browser owner = setUpOrganization();
        Browser herve = join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        UUID organization = organization();
        UUID herveId = userId(HERVE);
        event(organization, herveId, "document.status_changed", "2 days", "{\"title\": \"Barman\", \"before\": \"PUBLISHED\", \"after\": \"OBSOLETE\"}");
        event(organization, herveId, "document.viewed", "1 day", "{}");
        event(organization, null, "document.status_changed", "40 days", "{}");
        event(organization, herveId, "lore.future_action", "3 hours", "{}");

        assertThat(herve.get("/api/v1/admin/audit").code()).isEqualTo("admin_only");
        assertThat(herve.raw("GET", "/api/v1/admin/audit/export").status()).isEqualTo(403);

        JsonNode status = owner.get("/api/v1/admin/audit?category=status").json();
        assertThat(status.path("items").size()).isEqualTo(2);
        JsonNode first = status.at("/items/0");
        assertThat(first.path("action").asString()).isEqualTo("document.status_changed");
        assertThat(first.path("category").asString()).isEqualTo("status");
        assertThat(first.at("/actor/displayName").asString()).isEqualTo("Hervé Coulibaly");
        assertThat(first.at("/details/after").asString()).isEqualTo("OBSOLETE");
        assertThat(status.at("/items/1/actor").isNull()).isTrue();
        // A period, a person, the system, an action this version does not know.
        String since = java.time.Instant.now().minus(java.time.Duration.ofDays(30)).toString();
        assertThat(owner.get("/api/v1/admin/audit?category=status&from=" + since).json().path("items").size()).isEqualTo(1);
        assertThat(actions(owner.get("/api/v1/admin/audit?actor=" + herveId + "&category=view"))).containsExactly("document.viewed");
        assertThat(actions(owner.get("/api/v1/admin/audit?actor=system&category=status"))).containsExactly("document.status_changed");
        JsonNode other = owner.get("/api/v1/admin/audit?category=other").json();
        assertThat(other.at("/items/0/action").asString()).isEqualTo("lore.future_action");
        assertThat(other.at("/items/0/category").asString()).isEqualTo("other");

        // Pages by a cursor: no event twice, none lost.
        List<String> seen = new ArrayList<>();
        String next = null;
        do {
            JsonNode page = owner.get("/api/v1/admin/audit?size=2" + (next == null ? "" : "&before=" + next)).json();
            page.path("items").forEach(item -> seen.add(item.path("id").asString()));
            next = page.path("next").isNull() ? null : page.path("next").asString();
        } while (next != null);
        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).hasSize(jdbc.sql("select count(*) from audit_event where organization_id = ?").param(organization)
                .query(Integer.class).single());

        // Who left the organization is still named.
        assertThat(owner.delete("/api/v1/admin/members/" + herveId).status()).isEqualTo(204);
        assertThat(owner.get("/api/v1/admin/audit?category=status").json().at("/items/0/actor/displayName").asString())
                .isEqualTo("Hervé Coulibaly");

        Response wrong = owner.get("/api/v1/admin/audit?category=nope&actor=x&from=hier&before=y&size=500");
        assertThat(wrong.fieldErrors()).contains("category:invalid_value", "actor:invalid_value", "from:invalid_value",
                "before:invalid_value", "size:out_of_range");
        // A cursor written by hand, out of the dates PostgreSQL keeps: refused as any other.
        assertThat(owner.get("/api/v1/admin/audit?before=-5-1").fieldErrors()).contains("before:invalid_value");
        assertThat(owner.get("/api/v1/admin/audit?before=9223372036854775807-1").fieldErrors())
                .contains("before:invalid_value");
    }

    @Test
    void theExportIsACsvASpreadsheetCannotRunAndItIsRecorded() {
        Browser owner = setUpOrganization();
        UUID organization = organization();
        UUID ownerId = userId(OWNER_EMAIL);
        event(organization, ownerId, "document.imported", "1 hour",
                "{\"title\": \"=HYPERLINK(\\\"http://x\\\")\", \"space\": \"Infrastructure, réseau\"}");
        event(organization, ownerId, "document.imported", "2 hours", "{\"title\": \"Note;=1+2\", \"space\": \"Réseau\"}");

        Browser.Raw csv = owner.raw("GET", "/api/v1/admin/audit/export?category=import");
        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.header("Content-Type")).startsWith("text/csv");
        assertThat(csv.header("Content-Disposition")).contains("attachment").contains("lore-audit-");
        byte[] bytes = csv.body();
        assertThat(new byte[] {bytes[0], bytes[1], bytes[2]}).containsExactly(0xEF, 0xBB, 0xBF);
        String text = new String(bytes, StandardCharsets.UTF_8).substring(1);
        String[] lines = text.split("\r\n");
        assertThat(lines[0]).isEqualTo("occurred_at,actor_id,actor_name,actor_email,action,category,resource_type,"
                + "resource_id,title,space,ip,details");
        assertThat(lines).hasSize(3);
        // A semicolon splits a cell in a spreadsheet set for French: the value is quoted.
        assertThat(lines[2]).contains("\"Note;=1+2\"");
        assertThat(lines[1]).contains(",Wilfried Kouakou," + OWNER_EMAIL + ",document.imported,import,")
                .contains("\"'=HYPERLINK(\"\"http://x\"\")\"")
                .contains("\"Infrastructure, réseau\"");
        // The export is in the log, with its filters and its rows.
        assertThat(jdbc.sql("select (details->>'category') || ' ' || (details->>'rows') from audit_event where action = 'audit.exported'")
                .query(String.class).single()).isEqualTo("import 2");
    }

    @Test
    void anExportIsWrittenAsItIsReadAndOneAtATimeForAPerson() throws Exception {
        Browser owner = setUpOrganization();
        UUID organization = organization();
        jdbc.sql("""
                insert into audit_event (organization_id, actor_id, action, resource_type, resource_id, details, occurred_at)
                select ?, ?, 'document.viewed', 'document', gen_random_uuid()::text,
                    jsonb_build_object('title', 'Document ' || g, 'space', 'Infrastructure'), now() - g * interval '1 second'
                from generate_series(1, 100000) g
                """).params(organization, userId(OWNER_EMAIL)).update();

        // A first export, read slowly: the server waits for the reader, the export still running.
        HttpURLConnection slow = (HttpURLConnection) URI.create("http://localhost:" + port + "/api/v1/admin/audit/export?category=view")
                .toURL().openConnection();
        slow.setRequestProperty("Cookie", "LORE_SESSION=" + owner.cookie("LORE_SESSION"));
        slow.setRequestProperty("User-Agent", Browser.CHROME_WINDOWS);
        assertThat(slow.getResponseCode()).isEqualTo(200);
        java.io.ByteArrayOutputStream read = new java.io.ByteArrayOutputStream();
        try (InputStream body = slow.getInputStream()) {
            byte[] start = new byte[1024];
            int count = body.read(start);
            assertThat(count).isPositive();
            read.write(start, 0, count);
            assertThat(owner.get("/api/v1/admin/audit/export").code()).isEqualTo("export_running");
            body.transferTo(read);
        }
        // The header, then 100,000 rows.
        String csv = read.toString(StandardCharsets.UTF_8);
        assertThat(csv.split("\r\n", -1)).hasSize(100_002);
        assertThat(csv).endsWith("\r\n");
        assertThat(owner.raw("GET", "/api/v1/admin/audit/export?category=import").status()).isEqualTo(200);
    }

    private UUID organization() {
        return jdbc.sql("select id from organization").query(UUID.class).single();
    }

    private void event(UUID organization, UUID actor, String action, String ago, String details) {
        jdbc.sql("insert into audit_event (organization_id, actor_id, action, resource_type, resource_id, details, occurred_at)"
                + " values (?, ?, ?, 'document', ?, ?::jsonb, now() - ?::interval)")
                .params(organization, actor, action, UUID.randomUUID().toString(), details, ago)
                .update();
    }

    private static List<String> actions(Response page) {
        List<String> actions = new ArrayList<>();
        page.json().path("items").forEach(item -> actions.add(item.path("action").asString()));
        return actions;
    }
}
