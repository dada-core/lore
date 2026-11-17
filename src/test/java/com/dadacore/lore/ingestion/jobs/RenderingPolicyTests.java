package com.dadacore.lore.ingestion.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.dadacore.lore.ingestion.Rendering;
import org.junit.jupiter.api.Test;

/** What Lore keeps of a rendering, whatever the extraction process returns. */
class RenderingPolicyTests {

    private static String clean(String html) {
        Rendering rendering = RenderingPolicy.clean(new Rendering(List.of(html), List.of(), false));
        return rendering == null ? "" : rendering.parts().getFirst();
    }

    @Test
    void scriptsAndActiveContentAreLeftOut() {
        String html = clean("""
                <section id="h-1"><h2>Titre</h2>
                <script>alert(1)</script><p>texte <img src=x onerror=alert(1)></p>
                <iframe src="https://evil.example"></iframe><object data="x.swf"></object><embed src="x">
                <svg onload="alert(1)"><circle/></svg><math><mi>x</mi></math>
                <style>body{display:none}</style><p style="background:url(javascript:alert(1))">style</p>
                <form action="https://evil.example"><input name="password"><button>Envoyer</button></form>
                <table><tr><td><script>alert(2)</script>cellule</td></tr></table>
                <p onclick="alert(3)" onmouseover="alert(4)">clic</p>
                <base href="https://evil.example/"><meta http-equiv="refresh" content="0;url=https://evil.example">
                <link rel="stylesheet" href="https://evil.example/x.css">
                </section>""");

        assertThat(html).doesNotContainIgnoringCase("<script").doesNotContain("alert(")
                .doesNotContainIgnoringCase("<img").doesNotContainIgnoringCase("<iframe").doesNotContainIgnoringCase("<object")
                .doesNotContainIgnoringCase("<embed").doesNotContainIgnoringCase("<svg").doesNotContainIgnoringCase("<math")
                .doesNotContainIgnoringCase("<style").doesNotContain("style=").doesNotContainIgnoringCase("<form")
                .doesNotContainIgnoringCase("<input").doesNotContainIgnoringCase("<button").doesNotContain("onclick")
                .doesNotContain("onmouseover").doesNotContainIgnoringCase("<base").doesNotContainIgnoringCase("<meta")
                .doesNotContainIgnoringCase("<link").doesNotContain("evil.example");
        assertThat(html).contains("<section id=\"h-1\"><h2>Titre</h2>").contains("<td>cellule</td>").contains("clic");
    }

    @Test
    void linksGoToTheWebOrToAnAnchorOfLoreOnly() {
        String html = clean("""
                <p><a href="javascript:alert(1)">un</a> <a href="jav&#x61;script:alert(1)">deux</a>
                <a href=" JaVaScRiPt:alert(1)">trois</a> <a href="data:text/html,<script>alert(1)</script>">quatre</a>
                <a href="vbscript:x">cinq</a> <a href="/api/v1/session">six</a> <a href="../autre.md">sept</a>
                <a href="#root">huit</a> <a href="https://dada.group/docs" target="_top">neuf</a>
                <a href="#h-7">dix</a> <a href="mailto:astreinte@dada.group">onze</a> <a name="x">douze</a></p>""");

        assertThat(html).doesNotContainIgnoringCase("script:").doesNotContain("data:").doesNotContain("/api/v1")
                .doesNotContain("../autre.md").doesNotContain("#root").doesNotContain("_top").doesNotContain("name=");
        assertThat(html).contains("un").contains("deux").contains("sept").contains("douze")
                .contains("<a href=\"https://dada.group/docs\" rel=\"noopener noreferrer nofollow\">neuf</a>")
                .contains("<a href=\"#h-7\"").contains("<a href=\"mailto:astreinte&#64;dada.group\"");
    }

    @Test
    void onlyTheIdentifiersAndClassesOfLorePass() {
        String html = clean("""
                <section id="root"><p id="h-2">a</p><h2 id="h-3">b</h2><span class="lore-image">schéma</span>
                <span class="evil">c</span><a class="lore-image" href="https://dada.group/a.png" target="_blank">d</a>
                <ol start="3"><li>e</li></ol><ol start="x"><li>f</li></ol>
                <details open><summary>Plus</summary>g</details></section>""");

        // A document cannot take the identifier of an element of the interface (DOM clobbering).
        assertThat(html).doesNotContain("root").doesNotContain("id=\"h-2\"").doesNotContain("evil").doesNotContain("start=\"x\"");
        assertThat(html).contains("<h2 id=\"h-3\">b</h2>").contains("<span class=\"lore-image\">schéma</span>")
                .contains("<a class=\"lore-image\" href=\"https://dada.group/a.png\" target=\"_blank\"")
                .contains("<ol start=\"3\">").contains("<details><summary>Plus</summary>g</details>");
    }

    @Test
    void placesAreCheckedAgainstTheParts() {
        Rendering rendering = RenderingPolicy.clean(new Rendering(List.of("<section id=\"h-0\">a</section>",
                "<section id=\"h-1\"><h2>b</h2></section>"), List.of(
                new Rendering.Place("h-0", 1, 0, null),
                new Rendering.Place("h-1", 2, 1, "Titre\u0007 avec\ncontrôle"),
                new Rendering.Place("root", 1, 1, "x"),
                new Rendering.Place("h-2\" onload=\"x", 1, 1, "y"),
                new Rendering.Place("h-3", 9, 1, "hors des parties"),
                new Rendering.Place("h-4", 1, 12, "niveau impossible"),
                new Rendering.Place("h-5", 1, 2, "t".repeat(500))), true));

        assertThat(rendering.places()).extracting(Rendering.Place::anchor).containsExactly("h-0", "h-1", "h-5");
        assertThat(rendering.places().get(1).title()).isEqualTo("Titre  avec contrôle");
        assertThat(rendering.places().get(2).title()).hasSize(200);
        assertThat(rendering.truncated()).isTrue();
    }

    @Test
    void anEmptyRenderingIsNone() {
        assertThat(RenderingPolicy.clean(null)).isNull();
        assertThat(RenderingPolicy.clean(new Rendering(List.of(), List.of(), false))).isNull();
    }
}
