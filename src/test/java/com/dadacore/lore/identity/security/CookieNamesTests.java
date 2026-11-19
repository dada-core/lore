package com.dadacore.lore.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.dadacore.lore.LoreProperties;
import com.dadacore.lore.identity.organization.PublicUrl;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.util.unit.DataSize;

/**
 * Behind an HTTPS public address, the session and CSRF cookies carry the {@code __Host-} prefix:
 * the browser then only accepts them from this very host, over HTTPS, for the whole site (ASVS
 * 3.3.1). Over HTTP, they keep their usual names, which a browser would refuse with the prefix.
 */
class CookieNamesTests {

    private final SecurityConfiguration configuration = new SecurityConfiguration();

    @Test
    void anHttpsPublicAddressGivesHostPrefixedCookies() {
        PublicUrl https = publicUrl("https://lore.example.com");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        CookieSerializer sessions = configuration.sessionCookieSerializer(https);
        sessions.writeCookieValue(new CookieSerializer.CookieValue(request, response, "session-id"));
        CookieCsrfTokenRepository csrf = configuration.csrfTokenRepository(https);
        csrf.saveToken(csrf.generateToken(request), request, response);

        List<String> cookies = response.getHeaders("Set-Cookie");
        assertThat(cookies).hasSize(2);
        assertThat(cookies.get(0)).startsWith("__Host-LORE_SESSION=");
        assertThat(cookies.get(1)).startsWith("__Host-XSRF-TOKEN=");
        assertThat(cookies).allSatisfy(cookie -> assertThat(cookie)
                .contains("; Secure", "; Path=/;")
                .doesNotContainIgnoringCase("domain"));

        // And they are read back under these names.
        MockHttpServletRequest next = new MockHttpServletRequest();
        next.setCookies(cookie(response, "__Host-LORE_SESSION"), cookie(response, "__Host-XSRF-TOKEN"));
        assertThat(sessions.readCookieValues(next)).containsExactly("session-id");
        CsrfToken token = csrf.loadToken(next);
        assertThat(token).isNotNull();
        assertThat(token.getHeaderName()).isEqualTo("X-XSRF-TOKEN");
    }

    @Test
    void anHttpPublicAddressKeepsTheUsualNames() {
        for (PublicUrl url : new PublicUrl[] {publicUrl("http://lore.example.com"), publicUrl(null)}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            configuration.sessionCookieSerializer(url)
                    .writeCookieValue(new CookieSerializer.CookieValue(request, response, "session-id"));
            CookieCsrfTokenRepository csrf = configuration.csrfTokenRepository(url);
            csrf.saveToken(csrf.generateToken(request), request, response);

            List<String> cookies = response.getHeaders("Set-Cookie");
            assertThat(cookies.get(0)).startsWith("LORE_SESSION=").doesNotContain("Secure");
            assertThat(cookies.get(1)).startsWith("XSRF-TOKEN=").doesNotContain("Secure");
        }
    }

    private static Cookie cookie(MockHttpServletResponse response, String name) {
        String header = response.getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1, header.indexOf(';')));
    }

    private static PublicUrl publicUrl(String address) {
        return new PublicUrl(new LoreProperties(address == null ? null : URI.create(address), Duration.ofHours(8), null,
                Path.of("target/test-files"), DataSize.ofGigabytes(1), 2, Duration.ofSeconds(120), DataSize.ofMegabytes(512)));
    }
}
