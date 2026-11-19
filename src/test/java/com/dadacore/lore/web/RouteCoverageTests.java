package com.dadacore.lore.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import com.dadacore.lore.support.RouteMatrix;
import org.junit.jupiter.api.Test;

/**
 * Every route of the API answers for its rights somewhere: read from the API description, so that
 * a route added without its line in {@link RouteMatrix} fails here instead of passing unnoticed.
 */
class RouteCoverageTests extends ApiTest {

    /** A path with values in place of its parameters: identifiers that exist nowhere. */
    private static String filled(String path) {
        return path.replace("{number}", "1")
                .replace("{slug}", "nowhere")
                .replaceAll("\\{[^}]+}", "0193b1f2-0000-7000-8000-00000000dead");
    }

    private static boolean ofTheAdministration(String route) {
        return route.split(" ", 2)[1].startsWith(RouteMatrix.ADMINISTRATION);
    }

    private Set<String> routes() {
        return RouteMatrix.routes(browser().get("/api/v1/openapi.json"));
    }

    private static Response send(Browser browser, String route) {
        String[] parts = route.split(" ", 2);
        boolean withBody = !parts[0].equals("GET") && !parts[0].equals("DELETE");
        return browser.send(parts[0], filled(parts[1]), withBody ? Map.of() : null);
    }

    @Test
    void everyRouteIsPublicOfTheAdministrationOfOnesOwnAccountOrInAMatrix() {
        Set<String> routes = routes();
        Set<String> inMatrices = new TreeSet<>();
        RouteMatrix.MATRICES.values().forEach(inMatrices::addAll);

        List<String> unaccounted = routes.stream()
                .filter(route -> !RouteMatrix.PUBLIC.contains(route) && !ofTheAdministration(route)
                        && !RouteMatrix.OWN_ACCOUNT.contains(route) && !inMatrices.contains(route))
                .toList();
        assertThat(unaccounted).as("routes that no matrix of rights checks: add them to RouteMatrix").isEmpty();

        Set<String> written = new TreeSet<>(inMatrices);
        written.addAll(RouteMatrix.PUBLIC);
        written.addAll(RouteMatrix.OWN_ACCOUNT);
        written.removeAll(routes);
        assertThat(written).as("lines of RouteMatrix for routes that no longer exist").isEmpty();
    }

    @Test
    void withoutASessionOnlyThePublicRoutesAnswer() {
        setUpOrganization();
        Browser stranger = browser();
        List<String> open = new ArrayList<>();
        for (String route : routes()) {
            if (!RouteMatrix.PUBLIC.contains(route)) {
                Response response = send(stranger, route);
                if (response.status() != 401) {
                    open.add(route + " gives " + response.status());
                }
            }
        }
        assertThat(open).as("routes that answer without a session").isEmpty();
    }

    @Test
    void aMemberIsRefusedEveryRouteOfTheAdministration() {
        Browser owner = setUpOrganization();
        Browser member = join(owner, "MEMBER", "Eli Brou", "eli.brou@dada.group");
        List<String> routes = routes().stream().filter(RouteCoverageTests::ofTheAdministration).toList();
        assertThat(routes).isNotEmpty();
        List<String> open = new ArrayList<>();
        for (String route : routes) {
            Response response = send(member, route);
            if (response.status() != 403) {
                open.add(route + " gives " + response.status());
            }
        }
        assertThat(open).as("routes of the administration that a member reaches").isEmpty();
    }
}
