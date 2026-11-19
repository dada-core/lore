package com.dadacore.lore.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Who answers for the rights on each route of the API. A route added without a line here fails
 * {@code RouteCoverageTests}: it must be public, of the administration (refused to a member), of
 * one's own account, or probed by a matrix of rights for each kind of person.
 */
public final class RouteMatrix {

    /** Open without a session: setting up, signing in, joining, choosing a new password. */
    public static final Set<String> PUBLIC = Set.of(
            "GET /api/v1/session",
            "GET /api/v1/setup",
            "POST /api/v1/setup",
            "POST /api/v1/setup/verify-code",
            "POST /api/v1/auth/login",
            "POST /api/v1/invitations/inspect",
            "POST /api/v1/invitations/accept",
            "POST /api/v1/password-reset/inspect",
            "POST /api/v1/password-reset/complete");

    /** The administration: owners and administrators only. RouteCoverageTests refuses them to a member. */
    public static final String ADMINISTRATION = "/api/v1/admin/";

    /**
     * One's own account: the person signed in, and no one else (AccountApiTests). No identifier of
     * another person is accepted, except a session, which must be one's own.
     */
    public static final Set<String> OWN_ACCOUNT = Set.of(
            "POST /api/v1/auth/logout",
            "GET /api/v1/account",
            "PATCH /api/v1/account",
            "PUT /api/v1/account/email",
            "PUT /api/v1/account/password",
            "GET /api/v1/account/sessions",
            "POST /api/v1/account/sessions/sign-out-others",
            "DELETE /api/v1/account/sessions/{id}");

    /** The routes each matrix of rights probes for every kind of person. */
    public static final Map<String, Set<String>> MATRICES = Map.of(
            "WhoSeesWhatTests", Set.of(
                    "GET /api/v1/spaces",
                    "POST /api/v1/spaces",
                    "GET /api/v1/spaces/by-slug/{slug}",
                    "GET /api/v1/spaces/{id}",
                    "PATCH /api/v1/spaces/{id}",
                    "DELETE /api/v1/spaces/{id}",
                    "POST /api/v1/spaces/{id}/archive",
                    "POST /api/v1/spaces/{id}/unarchive",
                    "GET /api/v1/spaces/{spaceId}/folders",
                    "POST /api/v1/spaces/{spaceId}/folders",
                    "PATCH /api/v1/spaces/{spaceId}/folders/{folderId}",
                    "DELETE /api/v1/spaces/{spaceId}/folders/{folderId}",
                    "GET /api/v1/spaces/{spaceId}/members",
                    "POST /api/v1/spaces/{spaceId}/members",
                    "GET /api/v1/spaces/{spaceId}/members/candidates",
                    "PATCH /api/v1/spaces/{spaceId}/members/{userId}",
                    "DELETE /api/v1/spaces/{spaceId}/members/{userId}"),
            "WhoSeesWhichDocumentTests", Set.of(
                    "GET /api/v1/spaces/{spaceId}/documents",
                    "POST /api/v1/spaces/{spaceId}/documents",
                    "GET /api/v1/spaces/{spaceId}/documents/summary",
                    "GET /api/v1/documents/recent",
                    "GET /api/v1/documents/{id}",
                    "PATCH /api/v1/documents/{id}",
                    "DELETE /api/v1/documents/{id}",
                    "GET /api/v1/documents/{id}/access",
                    "PUT /api/v1/documents/{id}/access",
                    "GET /api/v1/documents/{id}/activity",
                    "POST /api/v1/documents/{id}/extraction/retry",
                    "POST /api/v1/documents/{id}/move",
                    "PUT /api/v1/documents/{id}/owner",
                    "PUT /api/v1/documents/{id}/status",
                    "GET /api/v1/documents/{id}/versions",
                    "POST /api/v1/documents/{id}/versions",
                    "GET /api/v1/documents/{id}/versions/{number}",
                    "GET /api/v1/documents/{id}/versions/{number}/file",
                    "GET /api/v1/documents/{id}/versions/{number}/rendering",
                    "GET /api/v1/imports/attention",
                    "GET /api/v1/imports/limits",
                    "GET /api/v1/search",
                    "GET /api/v1/search/titles",
                    "GET /api/v1/activity"));

    private RouteMatrix() {
    }

    /** The routes of the API description: {@code GET /api/v1/spaces/{id}}. */
    public static Set<String> routes(Response openApi) {
        Set<String> routes = new TreeSet<>();
        openApi.json().get("paths").properties().forEach(path ->
                path.getValue().propertyNames().forEach(method -> routes.add(method.toUpperCase() + " " + path.getKey())));
        return routes;
    }

    /** Fails unless the probes of this matrix sent a request to each route it answers for. */
    public static void assertProbed(Class<?> matrix) {
        Set<String> declared = MATRICES.get(matrix.getSimpleName());
        Set<String> reached = new TreeSet<>();
        for (String request : RouteLog.probed(matrix)) {
            route(request, declared).ifPresent(reached::add);
        }
        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(reached);
        assertThat(missing).as("routes of %s that no probe requests", matrix.getSimpleName()).isEmpty();
    }

    /**
     * The route a request reaches among these: the one that matches with the most literal parts,
     * so that {@code /documents/recent} is not taken for {@code /documents/{id}}.
     */
    private static Optional<String> route(String request, Set<String> routes) {
        Map<String, Integer> matching = new LinkedHashMap<>();
        for (String route : routes) {
            String[] parts = route.split(" ", 2);
            String pattern = Pattern.quote(parts[1]).replaceAll("\\{[^}]+}", "\\\\E[^/]+\\\\Q");
            if (request.startsWith(parts[0] + " ") && request.substring(parts[0].length() + 1).matches(pattern)) {
                matching.put(route, (int) List.of(parts[1].split("/")).stream().filter(part -> !part.startsWith("{")).count());
            }
        }
        return matching.entrySet().stream().max(Comparator.comparingInt(Map.Entry::getValue)).map(Map.Entry::getKey);
    }
}
