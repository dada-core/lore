package com.dadacore.lore.library.documents;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * The original file of a version, as the browser receives it: in one piece or by ranges, never
 * interpreted as a page (decisions 21 to 23 of the reader).
 *
 * <ul>
 *   <li>The type Lore established at the import, {@code nosniff}, and a policy that forbids
 *       everything to the file ({@code default-src 'none'; sandbox}).</li>
 *   <li>A strong {@code ETag}, the SHA-256 of the file: Tomcat never compresses such a response,
 *       which would spoil the ranges.</li>
 *   <li>{@code Cache-Control: private, no-cache}: the browser may keep the file, but asks again,
 *       and its right to it is checked again, at each opening.</li>
 *   <li>{@code Range}, {@code If-Range}, {@code If-None-Match}: Spring serves the ranges of a
 *       resource but ignores {@code If-Range}, hence this class.</li>
 * </ul>
 */
final class OriginalFile {

    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; sandbox";

    /** What the request asks for, decided before anything is written. */
    enum Answer {
        /** The whole file, 200. */
        WHOLE,
        /** One range or more, 206. */
        RANGES,
        /** The browser has it already (304), or a condition failed (412). */
        NOT_MODIFIED,
        /** A range outside the file, 416. */
        UNSATISFIABLE
    }

    private final Path file;
    private final long size;
    private final String mediaType;
    private final String etag;
    private final Instant lastModified;
    private final ContentDisposition disposition;
    private final String boundary = UUID.randomUUID().toString().replace("-", "");
    private Answer answer;
    private List<ResourceRegion> regions = List.of();
    private FileChannel channel;

    OriginalFile(Path file, long size, String mediaType, String sha256, Instant lastModified,
            ContentDisposition disposition) {
        this.file = file;
        this.size = size;
        this.mediaType = mediaType;
        this.etag = "\"" + sha256 + "\"";
        this.lastModified = lastModified;
        this.disposition = disposition;
    }

    /** Decides the answer and writes the headers; the body comes with {@link #send}. */
    Answer prepare(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.ETAG, etag);
        if (new ServletWebRequest(request, response).checkNotModified(etag, lastModified.toEpochMilli())) {
            answer = Answer.NOT_MODIFIED;
            return answer;
        }
        String range = request.getHeader(HttpHeaders.RANGE);
        if (range != null && ifRange(request.getHeader(HttpHeaders.IF_RANGE))) {
            try {
                regions = HttpRange.toResourceRegions(HttpRange.parseRanges(range), new FileSystemResource(file) {
                    @Override
                    public long contentLength() {
                        return size;
                    }
                });
            } catch (IllegalArgumentException unsatisfiable) {
                // Outside the file, malformed, too many ranges, or more bytes than the file holds.
                answer = Answer.UNSATISFIABLE;
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                response.setContentLength(0);
                return answer;
            }
        }
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        if (regions.isEmpty()) {
            answer = Answer.WHOLE;
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(mediaType);
            response.setContentLengthLong(size);
            return answer;
        }
        answer = Answer.RANGES;
        response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        if (regions.size() == 1) {
            ResourceRegion region = regions.getFirst();
            response.setContentType(mediaType);
            response.setHeader(HttpHeaders.CONTENT_RANGE, contentRange(region));
            response.setContentLengthLong(region.getCount());
        } else {
            response.setContentType("multipart/byteranges; boundary=" + boundary);
        }
        return answer;
    }

    /** True when the answer starts with the first byte of the file: a download, not its resumption. */
    boolean fromTheStart() {
        return answer == Answer.WHOLE || (answer == Answer.RANGES && regions.getFirst().getPosition() == 0);
    }

    /** True when the answer carries bytes of the file. */
    boolean hasBody(HttpServletRequest request) {
        return !"HEAD".equals(request.getMethod()) && (answer == Answer.WHOLE || answer == Answer.RANGES);
    }

    /** Opens the file, before anything is recorded or sent. */
    void open() throws IOException {
        channel = FileChannel.open(file, StandardOpenOption.READ);
    }

    /** Closes the file opened, when it was not sent. */
    void close() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                // Nothing was read from it.
            }
        }
    }

    /** Writes the body decided by {@link #prepare}, from the file {@linkplain #open opened}; nothing for {@code HEAD}. */
    void send(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!hasBody(request) || channel == null) {
            return;
        }
        try (FileChannel channel = this.channel) {
            OutputStream out = response.getOutputStream();
            if (answer == Answer.WHOLE) {
                copy(channel, 0, size, out);
            } else if (regions.size() == 1) {
                copy(channel, regions.getFirst().getPosition(), regions.getFirst().getCount(), out);
            } else {
                for (ResourceRegion region : regions) {
                    out.write(("\r\n--" + boundary + "\r\nContent-Type: " + mediaType + "\r\nContent-Range: "
                            + contentRange(region) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    copy(channel, region.getPosition(), region.getCount(), out);
                }
                out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
            }
            out.flush();
        }
    }

    /**
     * {@code If-Range}: the range is served only for the same file. A strong {@code ETag} must be
     * equal; a date must be the one of the file, to the second.
     */
    private boolean ifRange(String ifRange) {
        if (ifRange == null) {
            return true;
        }
        String value = ifRange.strip();
        if (value.startsWith("\"") || value.startsWith("W/")) {
            return value.equals(etag);
        }
        try {
            long date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            return date == lastModified.toEpochMilli() / 1000 * 1000;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private String contentRange(ResourceRegion region) {
        long end = region.getPosition() + region.getCount() - 1;
        return "bytes " + region.getPosition() + "-" + end + "/" + size;
    }

    /** Copies a slice of the file, read at its position: the channel stays open for the next one. */
    private static void copy(FileChannel channel, long position, long count, OutputStream out) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long done = 0;
        while (done < count) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), count - done));
            int read = channel.read(buffer, position + done);
            if (read < 0) {
                break;
            }
            out.write(buffer.array(), 0, read);
            done += read;
        }
    }
}
