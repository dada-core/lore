package com.dadacore.lore.library.documents;

import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;

import com.dadacore.lore.LoreProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads out the rest of an import refused before its file was read: an expired session, a missing
 * CSRF token, a space archived, a file announced too large. Otherwise Tomcat closes the connection
 * beyond 2 MB left unread, and the browser sees it cut instead of the answer. Only on this route,
 * and only up to the ceiling of the size: the other routes keep the limit of Tomcat.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ImportDrain extends OncePerRequestFilter {

    private static final Pattern IMPORT = Pattern.compile("/api/v1/spaces/[^/]+/documents/?");

    private final long limit;

    ImportDrain(LoreProperties properties) {
        this.limit = properties.maxUploadSize().toBytes() + LoreProperties.UPLOAD_FORM_MARGIN.toBytes();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !IMPORT.matcher(request.getRequestURI()).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            if (response.getStatus() >= 400 && request.getContentLengthLong() <= limit) {
                drain(request);
            }
        }
    }

    private void drain(HttpServletRequest request) {
        try {
            InputStream in = request.getInputStream();
            byte[] buffer = new byte[64 * 1024];
            long read = 0;
            int count;
            while (read <= limit && (count = in.read(buffer)) != -1) {
                read += count;
            }
        } catch (IOException | IllegalStateException e) {
            // Read already (the form was parsed), or the connection is gone.
        }
    }
}
