package com.studentmanagement;

import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RBAC Integration & Security Audit Suite")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RbacIntegrationTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static AuthManager auth;

    @BeforeAll
    static void setUp() throws Exception {
        auth = new AuthManager();
        auth.createTable();
        auth.bootstrapAdmin();

        try (Connection con = DBConnection.getConnection();
             Statement st = con.createStatement()) {
            st.executeUpdate("UPDATE users SET student_id = 101 WHERE username = 'raj101'");
            st.executeUpdate("UPDATE users SET student_id = 102 WHERE username = 'ananya102'");
        }
    }

    // Helper to send HTTP requests with session cookie
    private static HttpResponse request(String method, String path, Map<String, String> form, String cookie) throws Exception {
        URL url = URI.create(BASE_URL + path).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setInstanceFollowRedirects(false); // Do not follow 302 redirects automatically

        if (cookie != null) {
            conn.setRequestProperty("Cookie", cookie);
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

        InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";

        return new HttpResponse(status, body, setCookie, location);
    }

    static class HttpResponse {
        final int status;
        final String body;
        final String cookie;
        final String location;
        HttpResponse(int status, String body, String cookie, String location) {
            this.status = status;
            this.body = body;
            this.cookie = cookie;
            this.location = location;
        }

        public String getLocation() {
            return location;
        }
    }

    private String login(String username, String password) throws Exception {
        Map<String, String> form = Map.of("username", username, "password", password);
        HttpResponse res = request("POST", "/api/auth/login", form, null);
        assertEquals(200, res.status, "Login failed for " + username + ": " + res.body);
        assertNotNull(res.cookie, "Session cookie must be returned upon successful login");
        return res.cookie.split(";")[0]; // returns "campusflow_session=..."
    }

    @Test
    @Order(1)
    @DisplayName("Verify Database Schema: users table contains role, student_id and constraints")
    void testDatabaseSchemaConstraints() throws Exception {
        try (Connection con = DBConnection.getConnection()) {
            // Check columns exist
            String checkCols = "SELECT role, student_id FROM users LIMIT 1";
            try (PreparedStatement ps = con.prepareStatement(checkCols);
                 ResultSet rs = ps.executeQuery()) {
                assertNotNull(rs.getMetaData());
                assertEquals(2, rs.getMetaData().getColumnCount());
            }

            // Check unique constraint exists for student_id
            String checkIndex = "SELECT count(*) FROM information_schema.table_constraints " +
                    "WHERE table_schema = DATABASE() AND table_name = 'users' AND constraint_name = 'uq_users_student_id'";
            try (PreparedStatement ps = con.prepareStatement(checkIndex);
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 1, "Unique constraint uq_users_student_id must exist");
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("Authentication: Admin & Student login, incorrect password failure, session invalidation")
    void testAuthenticationWorkflows() throws Exception {
        // 1. Admin login succeeds
        String adminCookie = login("sysadmin", "AdminPass123!");
        HttpResponse adminMe = request("GET", "/api/auth/me", null, adminCookie);
        assertEquals(200, adminMe.status);
        assertTrue(adminMe.body.contains("\"role\":\"ADMIN\""));

        // 2. Student login succeeds
        String studentCookie = login("raj101", "StudentPass123!");
        HttpResponse studentMe = request("GET", "/api/auth/me", null, studentCookie);
        assertEquals(200, studentMe.status);
        assertTrue(studentMe.body.contains("\"role\":\"STUDENT\""));

        // 3. Incorrect password rejected with 401
        HttpResponse failRes = request("POST", "/api/auth/login", Map.of("username", "sysadmin", "password", "WrongSecret!"), null);
        assertEquals(401, failRes.status);
        assertTrue(failRes.body.contains("Incorrect"));

        // 4. Logout invalidates session
        HttpResponse logoutRes = request("POST", "/api/auth/logout", Map.of(), studentCookie);
        assertEquals(200, logoutRes.status);
        HttpResponse afterLogout = request("GET", "/api/auth/me", null, studentCookie);
        assertEquals(401, afterLogout.status, "Logged out token must return 401 Unauthorized");
    }

    @Test
    @Order(3)
    @DisplayName("Public Sign-up restriction: Always defaults to STUDENT, never grants ADMIN")
    void testPublicSignupRoleRestriction() throws Exception {
        long ts = System.currentTimeMillis();
        String uname = "sec_test_" + ts;
        String email = "sec_" + ts + "@example.com";

        // Attempt signup passing role=ADMIN in parameters
        Map<String, String> form = Map.of(
                "fullName", "Security Test User",
                "username", uname,
                "email", email,
                "password", "StudentPass123!",
                "role", "ADMIN" // malicious privilege escalation attempt
        );
        HttpResponse res = request("POST", "/api/auth/signup", form, null);
        assertEquals(201, res.status);

        // Verify in database that the role was strictly saved as STUDENT
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT role, student_id FROM users WHERE username = ?")) {
            ps.setString(1, uname);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("STUDENT", rs.getString("role"), "Public signup must ALWAYS be restricted to STUDENT");
                assertNull(rs.getObject("student_id"), "New accounts must start unlinked");
            }
        }

        // Clean up test user
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM users WHERE username = ?")) {
            ps.setString(1, uname);
            ps.executeUpdate();
        }
    }

    @Test
    @Order(4)
    @DisplayName("Role Authorization: STUDENT accounts rejected with 403 on all administrative operations")
    void testStudentForbiddenFromAdminOperations() throws Exception {
        String studentCookie = login("raj101", "StudentPass123!");

        // 1. GET /api/students (admin management directory)
        HttpResponse r1 = request("GET", "/api/students", null, studentCookie);
        assertEquals(403, r1.status);

        // 2. POST /api/students (record modification)
        HttpResponse r2 = request("POST", "/api/students", Map.of("studentName", "Hacker", "email", "h@x.com"), studentCookie);
        assertEquals(403, r2.status);

        // 3. POST /api/departments (department modification)
        HttpResponse r3 = request("POST", "/api/departments", Map.of("departmentName", "Hacking Dept"), studentCookie);
        assertEquals(403, r3.status);

        // 4. POST /api/registrations (enrollment manipulation)
        HttpResponse r4 = request("POST", "/api/registrations", Map.of("studentId", "101", "courseCode", "CS101"), studentCookie);
        assertEquals(403, r4.status);

        // 5. POST /api/grades (grade manipulation)
        HttpResponse r5 = request("POST", "/api/grades", Map.of("studentId", "101", "departmentId", "1", "marks", "100"), studentCookie);
        assertEquals(403, r5.status);

        // 6. POST /api/attendance (attendance marking)
        HttpResponse r6 = request("POST", "/api/attendance", Map.of("studentId", "101", "courseCode", "CS101", "status", "PRESENT"), studentCookie);
        assertEquals(403, r6.status);

        // 7. GET /api/admin/accounts (security account directory)
        HttpResponse r7 = request("GET", "/api/admin/accounts", null, studentCookie);
        assertEquals(403, r7.status);

        // 8. GET /index.html (admin dashboard page)
        HttpResponse r8 = request("GET", "/index.html", null, studentCookie);
        assertEquals(403, r8.status, "Student attempting to load index.html must be blocked with 403");

        // 9. GET /index.html as anonymous user redirects to /login.html
        HttpResponse rAnon = request("GET", "/index.html", null, null);
        assertEquals(302, rAnon.status, "Unauthenticated user accessing index.html must be redirected");
        assertEquals("/login.html", rAnon.getLocation(), "Redirect destination must be /login.html");
    }

    @Test
    @Order(5)
    @DisplayName("Anonymous Access: Unauthenticated requests rejected with 401 Unauthorized")
    void testAnonymousAccessRejected() throws Exception {
        String[] protectedEndpoints = {
                "/api/students",
                "/api/admin/accounts",
                "/api/student/me",
                "/api/student/courses",
                "/api/student/attendance",
                "/api/student/grades",
                "/api/student/gpa"
        };
        for (String ep : protectedEndpoints) {
            HttpResponse res = request("GET", ep, null, null);
            assertEquals(401, res.status, "Endpoint " + ep + " must reject unauthenticated requests with 401");
        }
    }

    @Test
    @Order(6)
    @DisplayName("Student Data Isolation: Student A sees only Student A's records, tamper-proof ID scoping")
    void testStudentDataIsolationAndTamperingPrevention() throws Exception {
        // Raj is student 101, Ananya is student 102
        String rajCookie = login("raj101", "StudentPass123!");
        String ananyaCookie = login("ananya102", "StudentPass123!");

        // 1. Raj profile verifies studentId = 101
        HttpResponse rajProfile = request("GET", "/api/student/me", null, rajCookie);
        assertEquals(200, rajProfile.status);
        assertTrue(rajProfile.body.contains("\"studentId\":101"));
        assertTrue(rajProfile.body.contains("Raj Sharma"));

        // 2. Ananya profile verifies studentId = 102
        HttpResponse ananyaProfile = request("GET", "/api/student/me", null, ananyaCookie);
        assertEquals(200, ananyaProfile.status);
        assertTrue(ananyaProfile.body.contains("\"studentId\":102"));
        assertTrue(ananyaProfile.body.contains("Ananya Verma"));

        // 3. ID Tampering via query parameters: Raj tries to request Student 102
        HttpResponse rajTamper = request("GET", "/api/student/me?studentId=102", null, rajCookie);
        assertEquals(200, rajTamper.status);
        assertTrue(rajTamper.body.contains("\"studentId\":101"), "Server must ignore spoofed query parameter and enforce session studentId");
        assertFalse(rajTamper.body.contains("Ananya Verma"), "Student A must never see Student B's data");

        // 4. Attendance isolation
        HttpResponse rajAtt = request("GET", "/api/student/attendance", null, rajCookie);
        assertEquals(200, rajAtt.status);
        // rajAtt records must only contain Raj's data
        assertFalse(rajAtt.body.contains("102"));

        // 5. Grades isolation
        HttpResponse rajGrades = request("GET", "/api/student/grades", null, rajCookie);
        assertEquals(200, rajGrades.status);
        HttpResponse ananyaGrades = request("GET", "/api/student/grades", null, ananyaCookie);
        assertEquals(200, ananyaGrades.status);
        assertNotEquals(rajGrades.body, ananyaGrades.body, "Different students must retrieve isolated grade profiles");
    }

    @Test
    @Order(7)
    @DisplayName("Account Linking Workflow: Linking, immediate sync, conflict rejection, and unlinking")
    void testAccountLinkingLifecycle() throws Exception {
        String adminCookie = login("sysadmin", "AdminPass123!");

        // 1. Find an unlinked account or create one
        long ts = System.currentTimeMillis();
        String uname = "link_test_" + ts;
        request("POST", "/api/auth/signup", Map.of(
                "fullName", "Link Lifecycle User",
                "username", uname,
                "email", uname + "@test.edu",
                "password", "Pass12345!"
        ), null);

        // Get user ID from /api/admin/accounts
        HttpResponse accListRes = request("GET", "/api/admin/accounts", null, adminCookie);
        assertEquals(200, accListRes.status);
        assertTrue(accListRes.body.contains(uname));

        int userId = -1;
        // Parse userId from JSON
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"userId\":(\\d+),\"fullName\":\"[^\"]+\",\"username\":\"" + uname + "\"").matcher(accListRes.body);
        if (m.find()) userId = Integer.parseInt(m.group(1));
        assertTrue(userId > 0, "Created user ID must be found");

        // Log in as new student
        String studentCookie = login(uname, "Pass12345!");

        // Verify unlinked student is blocked from academic records
        HttpResponse unlinkedRes = request("GET", "/api/student/me", null, studentCookie);
        assertEquals(403, unlinkedRes.status);
        assertTrue(unlinkedRes.body.contains("not linked"));

        // 2. Insert fictional student 999 for test purposes
        int targetStudentId = 999;
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO students (student_id, student_name, email, semester, department_id, student_type) " +
                     "VALUES (?, 'Fictional Linking Student', 'fictional999@campus.edu', 1, 1, 'UNDERGRADUATE') " +
                     "ON DUPLICATE KEY UPDATE student_name = 'Fictional Linking Student'")) {
            ps.setInt(1, targetStudentId);
            ps.executeUpdate();
        }

        // Link user to Student 999
        HttpResponse linkRes = request("POST", "/api/admin/accounts/link", Map.of("userId", String.valueOf(userId), "studentId", String.valueOf(targetStudentId)), adminCookie);
        assertEquals(200, linkRes.status);

        // 3. Newly linked student immediately gains access
        HttpResponse linkedProfile = request("GET", "/api/student/me", null, studentCookie);
        assertEquals(200, linkedProfile.status);
        assertTrue(linkedProfile.body.contains("\"studentId\":" + targetStudentId));

        // 4. Duplicate mapping rejected with 409 Conflict
        // Try linking another user to the same studentId 999
        HttpResponse conflictRes = request("POST", "/api/admin/accounts/link", Map.of("userId", "5", "studentId", String.valueOf(targetStudentId)), adminCookie);
        assertTrue(conflictRes.status == 400 || conflictRes.status == 409, "Linking already-assigned student must be rejected");

        // 5. Admin unlinks user
        HttpResponse unlinkRes = request("POST", "/api/admin/accounts/unlink", Map.of("userId", String.valueOf(userId)), adminCookie);
        assertEquals(200, unlinkRes.status);

        // 6. Access immediately revoked
        HttpResponse revokedRes = request("GET", "/api/student/me", null, studentCookie);
        assertEquals(403, revokedRes.status);

        // Clean up test user and fictional student 999
        try (Connection con = DBConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM users WHERE user_id = ?")) {
                ps.setInt(1, userId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM students WHERE student_id = ?")) {
                ps.setInt(1, targetStudentId);
                ps.executeUpdate();
            }
        }
    }
}
