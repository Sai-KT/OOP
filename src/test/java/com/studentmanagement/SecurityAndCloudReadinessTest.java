package com.studentmanagement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Security & Cloud Readiness Test Suite")
public class SecurityAndCloudReadinessTest {

    private static final String BASE_URL = "http://localhost:8080";

    @org.junit.jupiter.api.BeforeAll
    static void setUp() {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        ensureServerRunning();
    }

    private static void ensureServerRunning() {
        try (java.net.Socket s = new java.net.Socket("localhost", 8080)) {
            return;
        } catch (Exception ignored) {
        }
        try {
            WebServer.startServer(8080);
        } catch (Exception e) {
            System.err.println("Notice: Could not auto-start WebServer for test: " + e.getMessage());
        }
    }

    private static HttpResponse requestWithHeaders(String method, String path, Map<String, String> form,
                                                   Map<String, String> headers) throws Exception {
        URL url = URI.create(BASE_URL + path).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setInstanceFollowRedirects(false);

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }

        if (form != null && !form.isEmpty()) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : form.entrySet()) {
                if (sb.length() > 0) sb.append("&");
                sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                  .append("=")
                  .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        int status = conn.getResponseCode();
        String setCookie = conn.getHeaderField("Set-Cookie");
        String location = conn.getHeaderField("Location");
        String nosniff = conn.getHeaderField("X-Content-Type-Options");
        String frameOptions = conn.getHeaderField("X-Frame-Options");
        String referrerPolicy = conn.getHeaderField("Referrer-Policy");

        InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";

        return new HttpResponse(status, body, setCookie, location, nosniff, frameOptions, referrerPolicy);
    }

    static class HttpResponse {
        final int status;
        final String body;
        final String cookie;
        final String location;
        final String nosniff;
        final String frameOptions;
        final String referrerPolicy;

        HttpResponse(int status, String body, String cookie, String location,
                     String nosniff, String frameOptions, String referrerPolicy) {
            this.status = status;
            this.body = body;
            this.cookie = cookie;
            this.location = location;
            this.nosniff = nosniff;
            this.frameOptions = frameOptions;
            this.referrerPolicy = referrerPolicy;
        }
    }

    @Test
    @DisplayName("HTTP Security Headers: nosniff, DENY, strict-origin-when-cross-origin present on responses")
    void testSecurityHeaders() throws Exception {
        HttpResponse res = requestWithHeaders("GET", "/student-login.html", null, null);
        assertEquals(200, res.status);
        assertEquals("nosniff", res.nosniff, "X-Content-Type-Options must be nosniff");
        assertEquals("DENY", res.frameOptions, "X-Frame-Options must be DENY");
        assertEquals("strict-origin-when-cross-origin", res.referrerPolicy, "Referrer-Policy must be strict-origin-when-cross-origin");
    }

    @Test
    @DisplayName("Cloud Proxy HTTPS Cookie Detection: X-Forwarded-Proto enables Secure attribute automatically")
    void testProxySecureCookieDetection() throws Exception {
        // When request comes through cloud proxy (Render) with X-Forwarded-Proto: https
        HttpResponse res = requestWithHeaders("POST", "/api/auth/login",
                Map.of("username", "sysadmin", "password", "AdminPass123!", "portal", "ADMIN"),
                Map.of("X-Forwarded-Proto", "https"));

        assertEquals(200, res.status);
        assertNotNull(res.cookie, "Cookie must be returned");
        assertTrue(res.cookie.toLowerCase().contains("secure"), "Set-Cookie must contain 'Secure' when X-Forwarded-Proto is https");
        assertTrue(res.cookie.contains("HttpOnly"), "Set-Cookie must contain 'HttpOnly'");
        assertTrue(res.cookie.contains("SameSite=Lax"), "Set-Cookie must contain 'SameSite=Lax'");
    }

    @Test
    @DisplayName("CSRF Defense: Foreign origin on state-changing POST requests rejected with HTTP 403")
    void testCsrfForeignOriginRejection() throws Exception {
        // Attempting an administrative POST with a foreign Origin and Referer header (cross-site attempt)
        HttpResponse res = requestWithHeaders("POST", "/api/students",
                Map.of("studentId", "9999", "studentName", "Malicious", "email", "mal@x.com"),
                Map.of("Origin", "https://attacker-domain.evil.com", "Referer", "https://attacker-domain.evil.com/form.html"));

        assertEquals(403, res.status, "State-changing POST with foreign origin must be rejected with 403 Forbidden");
        assertTrue(res.body.contains("Cross-site request forbidden"), "Error message must indicate CSRF rejection");
    }

    @Test
    @DisplayName("Authentication: Invalid or forged session token returns HTTP 401")
    void testInvalidSessionToken() throws Exception {
        HttpResponse res = requestWithHeaders("GET", "/api/students", null,
                Map.of("Cookie", "EBSESSION=totally_invalid_forged_token_1234567890"));
        assertEquals(401, res.status, "Forged session token must be rejected with 401 Unauthorized");
    }

    @Test
    @DisplayName("Input Validation: Invalid numeric parameters return HTTP 400 error")
    void testInvalidNumericInputValidation() throws Exception {
        // Log in as admin
        HttpResponse loginRes = requestWithHeaders("POST", "/api/auth/login",
                Map.of("username", "sysadmin", "password", "AdminPass123!", "portal", "ADMIN"), null);
        String adminCookie = loginRes.cookie.split(";")[0];

        // Send non-numeric studentId to /api/registrations
        HttpResponse res = requestWithHeaders("POST", "/api/registrations",
                Map.of("studentId", "not_a_number", "courseCode", "IT101"),
                Map.of("Cookie", adminCookie));

        assertEquals(400, res.status, "Invalid non-numeric student ID must return 400 Bad Request");
        assertTrue(res.body.contains("valid student") || res.body.contains("error"), "Response must indicate validation failure");
    }
}
