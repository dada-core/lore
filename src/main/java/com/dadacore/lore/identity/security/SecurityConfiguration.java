package com.dadacore.lore.identity.security;

import com.dadacore.lore.identity.organization.PublicUrl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Access rules of the HTTP API.
 *
 * <ul>
 *   <li>Sessions stored in PostgreSQL, cookie {@code HttpOnly} and {@code SameSite=Lax}, marked
 *       {@code Secure} when the public address uses HTTPS.</li>
 *   <li>Behind an HTTPS public address ({@code LORE_PUBLIC_URL}), the session and CSRF cookies are
 *       named with the {@code __Host-} prefix: the browser keeps them to this very host, over
 *       HTTPS, for the whole site, and no other site of the domain can set them.</li>
 *   <li>CSRF protection on every request that changes something, sign-in included.</li>
 *   <li>Everything under {@code /api/} requires a session, except the few addresses listed below.
 *       The rights within the organization are checked by each service, from the database.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String SESSION_COOKIE = "LORE_SESSION";
    static final String CSRF_COOKIE = "XSRF-TOKEN";

    /** Only for a cookie marked {@code Secure}, with the path {@code /} and no domain. */
    static final String HOST_PREFIX = "__Host-";

    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "font-src 'self'",
            "connect-src 'self'",
            "object-src 'none'",
            "frame-ancestors 'none'",
            "base-uri 'self'",
            "form-action 'self'");

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, CookieCsrfTokenRepository csrfTokens,
            ProblemResponses problems) throws Exception {
        http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/api/v1/session", "/api/v1/setup").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/setup", "/api/v1/setup/verify-code",
                                "/api/v1/auth/login",
                                "/api/v1/password-reset/inspect", "/api/v1/password-reset/complete",
                                "/api/v1/invitations/inspect", "/api/v1/invitations/accept").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/openapi.json", "/api/v1/openapi.json/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/**", "/actuator/**").authenticated()
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                problems.write(response, 401, "unauthenticated"))
                        .accessDeniedHandler((request, response, exception) ->
                                problems.write(response, 403, exception instanceof CsrfException ? "csrf" : "forbidden")))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN))
                        .frameOptions(frame -> frame.deny()));
        return http.build();
    }

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(PublicUrl publicUrl) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // The interface reads it under both names (web/src/api/client.ts).
        repository.setCookieName(cookieName(CSRF_COOKIE, publicUrl));
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> {
            cookie.sameSite("Lax");
            if (publicUrl.fromEnvironment().isPresent()) {
                cookie.secure(publicUrl.isSecure());
            }
        });
        return repository;
    }

    @Bean
    CookieSerializer sessionCookieSerializer(PublicUrl publicUrl) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(cookieName(SESSION_COOKIE, publicUrl));
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        // Without LORE_PUBLIC_URL, the flag follows the request (HTTPS behind a proxy included).
        if (publicUrl.fromEnvironment().isPresent()) {
            serializer.setUseSecureCookie(publicUrl.isSecure());
        }
        return serializer;
    }

    /**
     * The name of a cookie: with the {@code __Host-} prefix when {@code LORE_PUBLIC_URL} uses HTTPS
     * (the cookie is then {@code Secure}). Over HTTP, a browser would refuse a prefixed cookie.
     */
    static String cookieName(String name, PublicUrl publicUrl) {
        return publicUrl.isSecure() ? HOST_PREFIX + name : name;
    }
}
