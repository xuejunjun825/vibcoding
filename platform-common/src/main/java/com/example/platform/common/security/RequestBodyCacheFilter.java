package com.example.platform.common.security;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Buffers the request body so both the permission interceptor and the controller can read it.
 *
 * <p>The servlet input stream can be consumed once. r18 needs two readers on
 * {@code POST /x/{id}/actions}: the interceptor reads {@code {"action": "approve"}} to decide, the
 * controller reads the same body through {@code @RequestBody}. Wrapping is the only alternative to
 * changing every action controller's signature (which would blow the AC-22 change budget).</p>
 *
 * <p>Only entity-bearing methods are buffered, and only up to {@link #MAX_BYTES}; a larger body is
 * passed through untouched (the interceptor then cannot name the action and fails closed with a
 * 403, which is the safe direction).</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestBodyCacheFilter extends OncePerRequestFilter {

    private static final long MAX_BYTES = 1 << 20;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String method = request.getMethod();
        boolean entity = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method);
        if (!entity || request.getContentLengthLong() > MAX_BYTES || isMultipart(request)) {
            chain.doFilter(request, response);
            return;
        }
        CachedBodyRequest cached = new CachedBodyRequest(request);
        cached.cache();
        chain.doFilter(cached, response);
    }

    /**
     * Multipart passes through untouched. A multipart body has no {@code action} field to read,
     * and buffering it breaks the container's own parser: the resolver then calls
     * {@code getParts()} on the wrapper, which delegates to the original request whose stream this
     * filter already read and closed — the upload dies with {@code IOException: Stream closed}
     * (HTTP 500) before any controller sees it.
     */
    private boolean isMultipart(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
    }

    /** Re-readable body; every {@code getInputStream()}/{@code getReader()} returns a fresh one. */
    public static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private byte[] body = new byte[0];

        CachedBodyRequest(HttpServletRequest request) {
            super(request);
        }

        void cache() throws IOException {
            try (var in = getRequest().getInputStream()) {
                body = in.readAllBytes();
            }
        }

        /** The raw body as text (empty when the request had no body). */
        public String bodyText() {
            return new String(body, charset());
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ArrayServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
        }

        private Charset charset() {
            String encoding = getCharacterEncoding();
            try {
                return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            } catch (RuntimeException ex) {
                return StandardCharsets.UTF_8;
            }
        }
    }

    /** {@code ServletInputStream} over a byte array: re-reading never consumes. */
    static final class ArrayServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream delegate;

        ArrayServletInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return delegate.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // Blocking reads only; the container never calls this on a synchronous dispatch.
        }
    }
}
