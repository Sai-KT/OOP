package com.studentmanagement;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;

/* ================================================================
   WEB SERVER FOR THE BROWSER DASHBOARD — ROLE-BASED ACCESS CONTROL
   - Uses JDK built-in HttpServer (zero external web frameworks).
   - Role-Based Access Control: ADMIN and STUDENT roles enforced in Java.
   - Never trusts browser-submitted role values; verifies authoritative
     session data against MySQL via AuthManager.
   - Admin routes strictly restricted to ADMIN role.
   - Static routes protect /index.html from anonymous and student users.
   ================================================================ */
public class WebServer {

    // UNIT II: static members shared by the whole server.
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    private static final Path FRONTEND = Paths.get(System.getenv().getOrDefault("FRONTEND_DIR", "frontend"))
            .toAbsolutePath().normalize();

    // UNIT II: objects of existing service classes (association / message passing).
    private static final StudentManager manager = new StudentManager();
    private static final AuthManager auth = new AuthManager();
    private static final AttendanceManager attendance = new AttendanceManager();
    private static final StudentPortalService studentService = new StudentPortalService();
    private static final String SESSION_COOKIE = "EBSESSION";

    private static HttpServer serverInstance;

    public static synchronized HttpServer startServer(int port) throws IOException {
        if (serverInstance != null) {
            return serverInstance;
        }
        // Create required tables if missing (fresh installation support)
        try {
            auth.createTable();
            attendance.createTable();
            try (Connection con = DBConnection.getConnection(); Statement st = con.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS department_grades ("
                        + "student_id INT NOT NULL, "
                        + "department_id INT NOT NULL, "
                        + "marks DECIMAL(5,2) NOT NULL, "
                        + "PRIMARY KEY(student_id,department_id), "
                        + "FOREIGN KEY(student_id) REFERENCES students(student_id), "
                        + "FOREIGN KEY(department_id) REFERENCES departments(department_id), "
                        + "CHECK(marks >= 0 AND marks <= 100))");
            }
            // Phase 2/3: Controlled initial administrator provisioning via environment variables
            auth.bootstrapAdmin();
        } catch (SQLException e) {
            System.err.println("Database startup initialization notice: " + e.getMessage());
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // ---------------- AUTHENTICATION ROUTES ----------------
        server.createContext("/api/auth/signup", WebServer::signup);
        server.createContext("/api/auth/login", WebServer::login);
        server.createContext("/api/auth/logout", WebServer::logout);
        server.createContext("/api/auth/me", WebServer::me);

        // ---------------- ADMIN ONLY ENDPOINTS ----------------
        server.createContext("/api/departments", adminOnly(WebServer::departments));
        server.createContext("/api/students", adminOnly(WebServer::students));
        server.createContext("/api/enrollments", adminOnly(WebServer::registrations));
        server.createContext("/api/registrations", adminOnly(WebServer::registrations));
        server.createContext("/api/grades", adminOnly(WebServer::grades));
        server.createContext("/api/gpa", adminOnly(WebServer::gpa));
        server.createContext("/api/attendance", adminOnly(WebServer::attendance));

        // ---------------- ADMIN ACCOUNT MANAGEMENT (PHASE 5) ----------------
        server.createContext("/api/admin/accounts", adminOnly(WebServer::adminAccounts));
        server.createContext("/api/admin/accounts/link", adminOnly(WebServer::adminLinkAccount));
        server.createContext("/api/admin/accounts/unlink", adminOnly(WebServer::adminUnlinkAccount));

        // ---------------- STUDENT ONLY ENDPOINTS (PHASE 4) ----------------
        server.createContext("/api/student/me", studentOnly(WebServer::studentMe));
        server.createContext("/api/student/courses", studentOnly(WebServer::studentCourses));
        server.createContext("/api/student/registrations", studentOnly(WebServer::studentRegistrations));
        server.createContext("/api/student/attendance", studentOnly(WebServer::studentAttendance));
        server.createContext("/api/student/grades", studentOnly(WebServer::studentGrades));
        server.createContext("/api/student/gpa", studentOnly(WebServer::studentGpa));

        // ---------------- SHARED AUTHENTICATED ENDPOINTS (ADMIN & STUDENT) ----------------
        // Course catalog view is available to all authenticated users
        server.createContext("/api/courses", authenticated(WebServer::courses));

        // ---------------- STATIC & HTML ROUTES ----------------
        server.createContext("/", WebServer::staticFile);

        server.setExecutor(null); // default executor
        server.start();
        serverInstance = server;
        System.out.println("EduBridge dashboard running at http://localhost:" + port);
        System.out.println("Serving frontend from: " + FRONTEND);
        return server;
    }

    public static synchronized void stopServer() {
        if (serverInstance != null) {
            serverInstance.stop(0);
            serverInstance = null;
        }
    }

    public static void main(String[] args) throws IOException {
        startServer(PORT);
    }

    // ===================== AUTHORIZATION WRAPPERS =====================

    // Enforces ADMIN-only authorization.
    // Returns 401 if unauthenticated, 403 if authenticated without ADMIN role.
    private static HttpHandler adminOnly(HttpHandler handler) {
        return ex -> {
            if (handleOptions(ex)) return;
            if ("POST".equalsIgnoreCase(ex.getRequestMethod()) && !isSameOrigin(ex)) {
                sendError(ex, 403, "Cross-site request forbidden: Invalid request origin.");
                return;
            }
            UserAccount user = auth.getUser(sessionToken(ex));
            if (user == null) {
                sendError(ex, 401, "Please log in first.");
                return;
            }
            if (user.getRole() != Role.ADMIN) {
                sendError(ex, 403, "Access denied: Administrator privileges required.");
                return;
            }
            handler.handle(ex);
        };
    }

    // Enforces authentication for shared endpoints (accessible by ADMIN and STUDENT).
    // Returns 401 if unauthenticated.
    private static HttpHandler authenticated(HttpHandler handler) {
        return ex -> {
            if (handleOptions(ex)) return;
            if ("POST".equalsIgnoreCase(ex.getRequestMethod()) && !isSameOrigin(ex)) {
                sendError(ex, 403, "Cross-site request forbidden: Invalid request origin.");
                return;
            }
            UserAccount user = auth.getUser(sessionToken(ex));
            if (user == null) {
                sendError(ex, 401, "Please log in first.");
                return;
            }
            handler.handle(ex);
        };
    }

    @FunctionalInterface
    private interface StudentHandler {
        void handle(HttpExchange ex, UserAccount user) throws Exception;
    }

    // Enforces STUDENT-only authorization with strict account linking and GET-only checks.
    // 401 if unauthenticated.
    // 403 if authenticated but not STUDENT role.
    // 405 if method is not GET.
    // 403 if student account is not linked to a student record.
    private static HttpHandler studentOnly(StudentHandler handler) {
        return ex -> {
            if (handleOptions(ex)) return;
            UserAccount user = auth.getUser(sessionToken(ex));
            if (user == null) {
                sendError(ex, 401, "Please log in first.");
                return;
            }
            if (user.getRole() != Role.STUDENT) {
                sendError(ex, 403, "Access denied: Student access required.");
                return;
            }
            if (!"GET".equals(ex.getRequestMethod())) {
                sendError(ex, 405, "Method not allowed. Supported method: GET.");
                return;
            }
            if (user.getStudentId() == null) {
                sendError(ex, 403, "Your account is not linked to a student record. Please contact an administrator to link your account.");
                return;
            }
            try {
                handler.handle(ex, user);
            } catch (SQLException e) {
                System.err.println("Student portal SQL error: " + e.getMessage());
                sendError(ex, 500, "Internal server error. Please try again later.");
            } catch (Exception e) {
                System.err.println("Student portal error: " + e.getMessage());
                sendError(ex, 500, "Internal server error. Please try again later.");
            }
        };
    }

    // ===================== LOGIN / SIGN-UP / AUTH =====================

    // POST /api/auth/signup (fullName, username, email, password)
    private static void signup(HttpExchange ex) throws IOException {
        if (handleOptions(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendError(ex, 405, "Method not allowed. Supported method: POST."); return; }
        Map<String, String> form = readForm(ex);
        String fullName = form.getOrDefault("fullName", "").trim();
        String username = form.getOrDefault("username", "").replaceAll("\\s+", "");
        String email = form.getOrDefault("email", "").replaceAll("\\s+", "").toLowerCase();
        String password = form.getOrDefault("password", "").replaceAll("\\s+", "");
        try {
            auth.signup(fullName, username, email, password);
            send(ex, 201, json("message", "Account created. Please sign in."));
        } catch (InvalidSignupException e) {
            sendError(ex, 400, e.getMessage());
        } catch (DuplicateUserException e) {
            sendError(ex, 409, e.getMessage());
        } catch (SQLException e) {
            System.err.println("Signup SQL error: " + e.getMessage());
            sendError(ex, 500, "Internal server error. Please try again later.");
        } finally {
            System.out.println("Sign-up attempt completed (web).");
        }
    }

    // POST /api/auth/login (username = username or email, password, optional portal = ADMIN | STUDENT)
    private static void login(HttpExchange ex) throws IOException {
        if (handleOptions(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendError(ex, 405, "Method not allowed. Supported method: POST."); return; }
        Map<String, String> form = readForm(ex);
        String portal = form.getOrDefault("portal", "").trim().toUpperCase();
        String username = form.getOrDefault("username", "").trim();
        String password = form.getOrDefault("password", "");

        if ("ADMIN".equals(portal) || "STUDENT".equals(portal)) {
            username = username.replaceAll("\\s+", "");
        }

        try {
            UserAccount user;
            if ("ADMIN".equals(portal) || "STUDENT".equals(portal)) {
                try {
                    user = auth.login(username, password.trim());
                } catch (InvalidLoginException ile) {
                    if (password.contains(" ")) {
                        user = auth.login(username, password.replaceAll("\\s+", ""));
                    } else {
                        throw ile;
                    }
                }
            } else {
                user = auth.login(username, password);
            }

            // Strict portal role validation: prevent students on admin login and admins on student login
            if ("ADMIN".equals(portal) && user.getRole() != Role.ADMIN) {
                sendError(ex, 403, "Access denied: This portal is strictly reserved for Administrators. Student accounts cannot sign in through the Admin Console.");
                return;
            }
            if ("STUDENT".equals(portal) && user.getRole() != Role.STUDENT) {
                sendError(ex, 403, "Access denied: This portal is strictly reserved for Students. Administrators must sign in through the Admin Console.");
                return;
            }
            String token = auth.createSession(user);
            ex.getResponseHeaders().add("Set-Cookie", sessionCookieHeader(ex, token));
            send(ex, 200, userJson(user));
        } catch (InvalidLoginException e) {
            sendError(ex, 401, e.getMessage());
        } catch (SQLException e) {
            System.err.println("Login SQL error: " + e.getMessage());
            sendError(ex, 500, "Internal server error. Please try again later.");
        } finally {
            System.out.println("Login attempt completed (web).");
        }
    }

    // POST /api/auth/logout
    private static void logout(HttpExchange ex) throws IOException {
        if (handleOptions(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendError(ex, 405, "Method not allowed. Supported method: POST."); return; }
        auth.logout(sessionToken(ex));
        ex.getResponseHeaders().add("Set-Cookie", expiredCookieHeader(ex));
        send(ex, 200, json("message", "Logged out."));
    }

    // GET /api/auth/me — returns current authoritative user data
    private static void me(HttpExchange ex) throws IOException {
        if (handleOptions(ex)) return;
        if (!"GET".equals(ex.getRequestMethod())) { sendError(ex, 405, "Method not allowed. Supported method: GET."); return; }
        UserAccount user = auth.getUser(sessionToken(ex));
        if (user == null) sendError(ex, 401, "Not logged in.");
        else send(ex, 200, userJson(user));
    }

    // ===================== ADMIN API HANDLERS =====================

    // GET /api/departments and POST /api/departments (ADMIN ONLY)
    private static void departments(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod()) && !"POST".equals(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed. Supported methods: GET, POST.");
            return;
        }
        try {
            if ("POST".equals(ex.getRequestMethod())) {
                Map<String, String> form = readForm(ex);
                int id = Integer.parseInt(form.getOrDefault("departmentId", "").trim());

                // Assign an existing student to this department.
                if ("assign".equals(form.get("action"))) {
                    int studentId = Integer.parseInt(form.getOrDefault("studentId", "").trim());
                    try (Connection con = DBConnection.getConnection();
                         PreparedStatement ps = con.prepareStatement("UPDATE students SET department_id=? WHERE student_id=?")) {
                        ps.setInt(1, id);
                        ps.setInt(2, studentId);
                        if (ps.executeUpdate() == 0) { sendError(ex, 404, "Student does not exist."); return; }
                    }
                    send(ex, 200, json("message", "Student moved to the department."));
                    return;
                }

                String name = form.getOrDefault("departmentName", "").trim();
                if (id < 1 || name.isEmpty()) {
                    sendError(ex, 400, "Enter a positive department ID and department name.");
                    return;
                }
                try (Connection con = DBConnection.getConnection();
                     PreparedStatement ps = con.prepareStatement(
                         "INSERT INTO departments(department_id, department_name) VALUES (?, ?)")) {
                    ps.setInt(1, id);
                    ps.setString(2, name);
                    ps.executeUpdate();
                }
                send(ex, 201, json("message", "Department saved."));
                return;
            }

            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT department_id, department_name FROM departments ORDER BY department_id");
                 ResultSet rs = ps.executeQuery()) {
                List<String> rows = new ArrayList<>();
                while (rs.next()) rows.add(json("departmentId", rs.getInt(1), "departmentName", rs.getString(2)));
                send(ex, 200, array(rows));
            }
        } catch (NumberFormatException e) {
            sendError(ex, 400, "Department ID must be a number.");
        } catch (SQLIntegrityConstraintViolationException e) {
            sendError(ex, 409, "That department ID already exists, or the department does not exist.");
        } catch (SQLException e) {
            System.err.println("Departments SQL error: " + e.getMessage());
            sendError(ex, 500, "Internal server error. Please try again later.");
        }
    }

    // GET /api/students | POST /api/students (ADMIN ONLY)
    private static void students(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod()) && !"POST".equals(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed. Supported methods: GET, POST.");
            return;
        }
        try {
            if ("POST".equals(ex.getRequestMethod())) {
                Map<String, String> form = readForm(ex);
                int id = Integer.parseInt(form.getOrDefault("studentId", "").trim());
                String name = form.getOrDefault("studentName", "").trim();
                String email = form.getOrDefault("email", "").trim();
                int semester = Integer.parseInt(form.getOrDefault("semester", "").trim());
                int departmentId = Integer.parseInt(form.getOrDefault("departmentId", "").trim());
                String type = form.getOrDefault("studentType", "UNDERGRADUATE").trim();

                if (name.isEmpty() || !email.contains("@")) {
                    sendError(ex, 400, "Enter a name and a valid email.");
                    return;
                }
                if (semester < 1 || semester > 8) {
                    sendError(ex, 400, "Semester must be between 1 and 8.");
                    return;
                }

                // UNIT III: base-class reference holding a child object (runtime polymorphism).
                Student student = "POSTGRADUATE".equalsIgnoreCase(type)
                        ? new PostgraduateStudent(id, name, email, semester, departmentId)
                        : new UndergraduateStudent(id, name, email, semester, departmentId);
                manager.addStudent(student); // existing JDBC method from Main.java
                send(ex, 201, json("message", "Student saved using JDBC."));
                return;
            }

            manager.loadStudents(); // existing JDBC method: fills Map<Integer, Student>
            Map<Integer, String> deptNames = departmentNames();
            List<String> rows = new ArrayList<>();
            for (Student s : manager.getStudents().values()) {
                rows.add(json(
                        "studentId", s.getStudentId(),
                        "studentName", s.getStudentName(),
                        "email", s.getEmail(),
                        "semester", s.getSemester(),
                        "departmentId", s.getDepartmentId(),
                        "departmentName", deptNames.getOrDefault(s.getDepartmentId(), ""),
                        "studentType", s.getStudentType(),
                        // UNIT III: dynamic binding — the overridden method of the real object runs.
                        "guidance", s.getRegistrationGuidance(),
                        "gpa", calculateDepartmentGpa(s.getStudentId())));
            }
            send(ex, 200, array(rows));
        } catch (NumberFormatException e) {
            sendError(ex, 400, "Student ID, semester and department must be numbers.");
        } catch (SQLIntegrityConstraintViolationException e) {
            sendError(ex, 409, "A student with this ID or email already exists, or the department is invalid.");
        } catch (SQLException e) {
            System.err.println("Students SQL error: " + e.getMessage());
            sendError(ex, 500, "Internal server error. Please try again later.");
        }
    }

    // GET /api/courses — course catalog view (ADMIN AND STUDENT)
    private static void courses(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed. Supported method: GET.");
            return;
        }
        try {
            manager.loadCourses(); // existing JDBC method: fills Map<String, Course>
            Map<String, int[]> counts = new HashMap<>();
            String sql = "SELECT course_code, "
                    + "SUM(CASE WHEN status='CONFIRMED' THEN 1 ELSE 0 END), "
                    + "SUM(CASE WHEN status='WAITLISTED' THEN 1 ELSE 0 END) "
                    + "FROM registrations GROUP BY course_code";
            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) counts.put(rs.getString(1), new int[]{rs.getInt(2), rs.getInt(3)});
            }

            List<Course> list = new ArrayList<>(manager.getCourses().values());
            list.sort(Comparator.comparing(Course::getCourseCode));
            List<String> rows = new ArrayList<>();
            for (Course c : list) {
                int[] count = counts.getOrDefault(c.getCourseCode(), new int[]{0, 0});
                rows.add(json(
                        "courseCode", c.getCourseCode(),
                        "courseName", c.getCourseName(),
                        "credits", c.getCredits(),
                        "capacity", c.getCapacity(),
                        "prerequisiteCode", c.getPrerequisiteCode(),
                        "confirmed", count[0],
                        "waitlisted", count[1]));
            }
            send(ex, 200, array(rows));
        } catch (SQLException e) {
            System.err.println("Courses SQL error: " + e.getMessage());
            sendError(ex, 500, "Internal server error. Please try again later.");
        }
    }

    // GET /api/registrations | POST /api/registrations (ADMIN ONLY)
    private static void registrations(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod()) && !"POST".equals(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed. Supported methods: GET, POST.");
            return;
        }
        if ("POST".equals(ex.getRequestMethod())) {
            Map<String, String> form = readForm(ex);
            try {
                int studentId = Integer.parseInt(form.getOrDefault("studentId", "").trim());
                String courseCode = form.getOrDefault("courseCode", "").trim().toUpperCase();
                String status;
                try (Connection con = DBConnection.getConnection()) {
                    con.setAutoCommit(false);
                    try {
                        try (PreparedStatement ps = con.prepareStatement("SELECT student_id FROM students WHERE student_id=?")) {
                            ps.setInt(1, studentId); try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new InvalidRegistrationException("Student does not exist."); }
                        }
                        int capacity; int confirmed;
                        try (PreparedStatement ps = con.prepareStatement("SELECT c.capacity, (SELECT COUNT(*) FROM registrations r WHERE r.course_code=c.course_code AND r.status='CONFIRMED') FROM courses c WHERE c.course_code=? FOR UPDATE")) {
                            ps.setString(1, courseCode); try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new InvalidRegistrationException("Course does not exist."); capacity=rs.getInt(1); confirmed=rs.getInt(2); }
                        }
                        try (PreparedStatement ps = con.prepareStatement("SELECT registration_id FROM registrations WHERE student_id=? AND course_code=?")) {
                            ps.setInt(1, studentId); ps.setString(2, courseCode); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) throw new InvalidRegistrationException("This student is already enrolled in this course."); }
                        }
                        status = confirmed < capacity ? "CONFIRMED" : "WAITLISTED";
                        try (PreparedStatement ps = con.prepareStatement("INSERT INTO registrations(student_id,course_code,status) VALUES(?,?,?)")) {
                            ps.setInt(1, studentId); ps.setString(2, courseCode); ps.setString(3, status); ps.executeUpdate();
                        }
                        con.commit();
                    } catch (SQLException | InvalidRegistrationException e) { con.rollback(); throw e; }
                }
                send(ex, 201, json("status", status, "message", status.equals("CONFIRMED") ? "Enrollment added successfully." : "Course is full; student added to the waitlist."));
            } catch (InvalidRegistrationException e) {
                // UNIT IV: user-defined exception -> clear message instead of a database error.
                sendError(ex, 400, "Enrollment error: " + e.getMessage());
            } catch (NumberFormatException e) {
                sendError(ex, 400, "Select a valid student.");
            } catch (SQLException e) {
                System.err.println("Registration SQL error: " + e.getMessage());
                sendError(ex, 500, "Internal server error. Please try again later.");
            } finally {
                System.out.println("Enrollment attempt completed (web).");
            }
            return;
        }

        String sql = "SELECT r.registration_id, r.student_id, s.student_name, r.course_code, c.course_name, "
                + "r.status, r.registration_date FROM registrations r "
                + "JOIN students s ON s.student_id = r.student_id "
                + "JOIN courses c ON c.course_code = r.course_code ORDER BY r.registration_id";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                Timestamp date = rs.getTimestamp(7);
                rows.add(json(
                        "registrationId", rs.getInt(1),
                        "studentId", rs.getInt(2),
                        "studentName", rs.getString(3),
                        "courseCode", rs.getString(4),
                        "courseName", rs.getString(5),
                        "status", rs.getString(6),
                        "registrationDate", date == null ? null : date.toString()));
            }
            send(ex, 200, array(rows));
        } catch (SQLException e) {
            System.err.println("Registrations list SQL error: " + e.getMessage());
            sendError(ex, 500, "Internal server error. Please try again later.");
        }
    }

    // GET /api/grades | POST /api/grades (ADMIN ONLY)
    private static void grades(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod()) && !"POST".equals(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed. Supported methods: GET, POST.");
            return;
        }
        if ("POST".equals(ex.getRequestMethod())) {
            Map<String, String> form = readForm(ex);
            try {
                int studentId = Integer.parseInt(form.getOrDefault("studentId", "").trim());
                int departmentId = Integer.parseInt(form.getOrDefault("departmentId", "").trim());
                double marks = Double.parseDouble(form.getOrDefault("marks", "").trim());
                if (marks < 0 || marks > 100) { sendError(ex, 400, "Marks must be between 0 and 100."); return; }
                try (Connection con = DBConnection.getConnection()) {
                    try (PreparedStatement ps = con.prepareStatement("SELECT student_id FROM students WHERE student_id=?")) {
                        ps.setInt(1, studentId); try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) { sendError(ex, 400, "Student does not exist."); return; } }
                    }
                    try (PreparedStatement ps = con.prepareStatement("SELECT department_id FROM departments WHERE department_id=?")) {
                        ps.setInt(1, departmentId); try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) { sendError(ex, 400, "Department does not exist."); return; } }
                    }
                    String sql = "INSERT INTO department_grades(student_id,department_id,marks) VALUES(?,?,?) ON DUPLICATE KEY UPDATE marks=VALUES(marks)";
                    try (PreparedStatement ps = con.prepareStatement(sql)) { ps.setInt(1, studentId); ps.setInt(2, departmentId); ps.setDouble(3, marks); ps.executeUpdate(); }
                }
                send(ex, 201, json("message", "Department grade saved.", "gpa", calculateDepartmentGpa(studentId)));
            } catch (NumberFormatException e) {
                sendError(ex, 400, "Select a student and department and enter valid marks.");
            } catch (SQLException e) {
                System.err.println("Grades POST SQL error: " + e.getMessage());
                sendError(ex, 500, "Internal server error. Please try again later.");
            }
            return;
        }
        String sql = "SELECT g.student_id,s.student_name,g.department_id,d.department_name,g.marks FROM department_grades g JOIN students s ON s.student_id=g.student_id JOIN departments d ON d.department_id=g.department_id ORDER BY g.student_id,g.department_id";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            List<String> rows = new ArrayList<>();
            while (rs.next()) rows.add(json("studentId", rs.getInt(1), "studentName", rs.getString(2), "departmentId", rs.getInt(3), "departmentName", rs.getString(4), "marks", rs.getDouble(5)));
            send(ex, 200, array(rows));
        } catch (SQLException e) {
            System.err.println("Grades GET SQL error: " + e.getMessage());
            sendError(ex, 500, "Internal server error. Please try again later.");
        }
    }

    private static double calculateDepartmentGpa(int studentId) throws SQLException {
        String sql = "SELECT AVG(marks) FROM department_grades WHERE student_id=?";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double avg = rs.getDouble(1);
                    return rs.wasNull() ? 0.0 : Math.round(avg * 10.0) / 100.0;
                }
                return 0.0;
            }
        }
    }

    // GET /api/gpa (ADMIN ONLY)
    private static void gpa(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed. Supported method: GET.");
            return;
        }
        try {
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
            int studentId = Integer.parseInt(query.getOrDefault("studentId", "").trim());
            send(ex, 200, json("studentId", studentId, "gpa", calculateDepartmentGpa(studentId)));
        } catch (NumberFormatException e) {
            sendError(ex, 400, "Select a valid student.");
        } catch (SQLException e) {
            System.err.println("GPA SQL error: " + e.getMessage());
            sendError(ex, 500, "Internal server error. Please try again later.");
        }
    }

    // GET /api/attendance | POST /api/attendance (ADMIN ONLY)
    private static void attendance(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod()) && !"POST".equals(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed. Supported methods: GET, POST.");
            return;
        }
        if ("POST".equals(ex.getRequestMethod())) {
            Map<String, String> form = readForm(ex);
            try {
                int studentId = Integer.parseInt(form.getOrDefault("studentId", "").trim());
                String courseCode = form.getOrDefault("courseCode", "").trim().toUpperCase();
                attendance.markAttendance(studentId, courseCode, form.getOrDefault("date", ""), form.getOrDefault("status", ""));
                send(ex, 201, json("message", "Attendance saved."));
            } catch (InvalidAttendanceException e) {
                sendError(ex, 400, "Attendance error: " + e.getMessage());
            } catch (NumberFormatException e) {
                sendError(ex, 400, "Select a valid student.");
            } catch (SQLException e) {
                System.err.println("Attendance POST SQL error: " + e.getMessage());
                sendError(ex, 500, "Internal server error. Please try again later.");
            }
            return;
        }
        try {
            List<String> rows = new ArrayList<>();
            for (AttendanceRecord a : attendance.getAll()) {
                rows.add(json("attendanceId", a.getAttendanceId(), "studentId", a.getStudentId(),
                        "studentName", a.getStudentName(), "courseCode", a.getCourseCode(),
                        "courseName", a.getCourseName(), "date", a.getAttendanceDate(),
                        "status", a.getStatus()));
            }
            send(ex, 200, array(rows));
        } catch (SQLException e) {
            System.err.println("Attendance GET SQL error: " + e.getMessage());
            sendError(ex, 500, "Internal server error. Please try again later.");
        }
    }

    // ===================== ADMIN ACCOUNT MANAGEMENT HANDLERS (PHASE 5) =====================

    // GET /api/admin/accounts (ADMIN ONLY)
    private static void adminAccounts(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed. Supported method: GET.");
            return;
        }
        String sql = "SELECT u.user_id, u.full_name, u.username, u.email, u.role, u.student_id, s.student_name "
                + "FROM users u "
                + "LEFT JOIN students s ON u.student_id = s.student_id "
                + "ORDER BY u.user_id ASC";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> accounts = new ArrayList<>();
            while (rs.next()) {
                int sid = rs.getInt("student_id");
                Integer studentId = rs.wasNull() ? null : sid;
                String studentName = rs.getString("student_name");
                accounts.add(json(
                        "userId", rs.getInt("user_id"),
                        "fullName", rs.getString("full_name"),
                        "username", rs.getString("username"),
                        "email", rs.getString("email"),
                        "role", rs.getString("role"),
                        "studentId", studentId,
                        "studentName", studentName
                ));
            }
            send(ex, 200, array(accounts));
        } catch (SQLException e) {
            System.err.println("Admin accounts list SQL error: " + e.getMessage());
            sendError(ex, 500, "Internal server error. Please try again later.");
        }
    }

    // POST /api/admin/accounts/link (ADMIN ONLY)
    // Parameters: userId, studentId
    private static void adminLinkAccount(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed. Supported method: POST.");
            return;
        }
        Map<String, String> params = readForm(ex);
        int userId;
        int studentId;
        try {
            userId = Integer.parseInt(params.getOrDefault("userId", "").trim());
            studentId = Integer.parseInt(params.getOrDefault("studentId", "").trim());
            if (userId <= 0 || studentId <= 0) {
                sendError(ex, 400, "userId and studentId must be positive integers.");
                return;
            }
        } catch (NumberFormatException e) {
            sendError(ex, 400, "Valid userId and studentId are required.");
            return;
        }

        try (Connection con = DBConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                // 1. Verify target user exists and is a STUDENT
                String targetUsername;
                Role targetRole;
                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT username, role FROM users WHERE user_id = ? FOR UPDATE")) {
                    ps.setInt(1, userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            con.rollback();
                            sendError(ex, 404, "User account not found (userId: " + userId + ").");
                            return;
                        }
                        targetUsername = rs.getString("username");
                        targetRole = Role.fromString(rs.getString("role"));
                    }
                }

                if (targetRole != Role.STUDENT) {
                    con.rollback();
                    sendError(ex, 400, "Only STUDENT accounts can be linked to student records. Target user has role " + targetRole + ".");
                    return;
                }

                // 2. Verify target student exists in students table
                String studentName;
                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT student_name FROM students WHERE student_id = ?")) {
                    ps.setInt(1, studentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            con.rollback();
                            sendError(ex, 404, "Student record not found (studentId: " + studentId + ").");
                            return;
                        }
                        studentName = rs.getString("student_name");
                    }
                }

                // 3. Verify student is not already linked to another user account
                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT user_id, username FROM users WHERE student_id = ? AND user_id != ?")) {
                    ps.setInt(1, studentId);
                    ps.setInt(2, userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            int otherUserId = rs.getInt("user_id");
                            String otherUsername = rs.getString("username");
                            con.rollback();
                            sendError(ex, 409, "Student ID " + studentId + " (" + studentName + ") is already linked to user account '"
                                    + otherUsername + "' (userId: " + otherUserId + ").");
                            return;
                        }
                    }
                }

                // 4. Update link
                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE users SET student_id = ? WHERE user_id = ?")) {
                    ps.setInt(1, studentId);
                    ps.setInt(2, userId);
                    ps.executeUpdate();
                }

                con.commit();

                // Refresh memory session if target user is currently active
                auth.refreshUserStudentLink(userId, studentId);

                send(ex, 200, json(
                        "message", "User account '" + targetUsername + "' successfully linked to student " + studentId + " (" + studentName + ").",
                        "userId", userId,
                        "username", targetUsername,
                        "studentId", studentId,
                        "studentName", studentName
                ));
            } catch (SQLException e) {
                con.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("Admin link account SQL error: " + e.getMessage());
            sendError(ex, 500, "Internal server error. Please try again later.");
        }
    }

    // POST /api/admin/accounts/unlink (ADMIN ONLY)
    // Parameters: userId
    private static void adminUnlinkAccount(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed. Supported method: POST.");
            return;
        }
        Map<String, String> params = readForm(ex);
        int userId;
        try {
            userId = Integer.parseInt(params.getOrDefault("userId", "").trim());
            if (userId <= 0) {
                sendError(ex, 400, "userId must be a positive integer.");
                return;
            }
        } catch (NumberFormatException e) {
            sendError(ex, 400, "Valid userId is required.");
            return;
        }

        try (Connection con = DBConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                // 1. Verify target user exists
                String targetUsername;
                Role targetRole;
                Integer currentLinkedStudentId;
                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT username, role, student_id FROM users WHERE user_id = ? FOR UPDATE")) {
                    ps.setInt(1, userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            con.rollback();
                            sendError(ex, 404, "User account not found (userId: " + userId + ").");
                            return;
                        }
                        targetUsername = rs.getString("username");
                        targetRole = Role.fromString(rs.getString("role"));
                        int sid = rs.getInt("student_id");
                        currentLinkedStudentId = rs.wasNull() ? null : sid;
                    }
                }

                if (targetRole != Role.STUDENT) {
                    con.rollback();
                    sendError(ex, 400, "Cannot unlink non-student accounts.");
                    return;
                }

                if (currentLinkedStudentId == null) {
                    con.rollback();
                    sendError(ex, 400, "User account '" + targetUsername + "' is not currently linked to any student record.");
                    return;
                }

                // 2. Unlink in users table
                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE users SET student_id = NULL WHERE user_id = ?")) {
                    ps.setInt(1, userId);
                    ps.executeUpdate();
                }

                con.commit();

                // Refresh memory session if active
                auth.refreshUserStudentLink(userId, null);

                send(ex, 200, json(
                        "message", "Student record successfully unlinked from user account '" + targetUsername + "'.",
                        "userId", userId,
                        "username", targetUsername,
                        "previousStudentId", currentLinkedStudentId,
                        "studentId", null
                ));
            } catch (SQLException e) {
                con.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("Admin unlink account SQL error: " + e.getMessage());
            sendError(ex, 500, "Internal server error. Please try again later.");
        }
    }

    // ===================== STUDENT PORTAL HANDLERS (PHASE 4) =====================

    // GET /api/student/me
    private static void studentMe(HttpExchange ex, UserAccount user) throws IOException, SQLException {
        String profile = studentService.getProfile(user.getStudentId());
        if (profile == null) {
            sendError(ex, 404, "Student record not found.");
            return;
        }
        send(ex, 200, profile);
    }

    // GET /api/student/courses
    private static void studentCourses(HttpExchange ex, UserAccount user) throws IOException, SQLException {
        send(ex, 200, studentService.getCourses(user.getStudentId()));
    }

    // GET /api/student/registrations
    private static void studentRegistrations(HttpExchange ex, UserAccount user) throws IOException, SQLException {
        send(ex, 200, studentService.getRegistrations(user.getStudentId()));
    }

    // GET /api/student/attendance
    private static void studentAttendance(HttpExchange ex, UserAccount user) throws IOException, SQLException {
        send(ex, 200, studentService.getAttendance(user.getStudentId()));
    }

    // GET /api/student/grades
    private static void studentGrades(HttpExchange ex, UserAccount user) throws IOException, SQLException {
        send(ex, 200, studentService.getGrades(user.getStudentId()));
    }

    // GET /api/student/gpa
    private static void studentGpa(HttpExchange ex, UserAccount user) throws IOException, SQLException {
        double gpa = studentService.getGpa(user.getStudentId());
        send(ex, 200, json("studentId", user.getStudentId(), "gpa", gpa));
    }

    // ===================== STATIC & HTML ROUTE PROTECTION =====================

    private static void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        UserAccount user = auth.getUser(sessionToken(ex));

        // 1. Root route: redirect based on authoritative role
        if (path.equals("/")) {
            if (user == null) {
                redirect(ex, "/student-login.html");
                return;
            }
            if (user.getRole() == Role.ADMIN) {
                redirect(ex, "/index.html");
                return;
            }
            if (user.getRole() == Role.STUDENT) {
                redirect(ex, "/student.html");
                return;
            }
        }

        // 2. Admin dashboard route (/index.html) - strictly ADMIN only
        if (path.equals("/index.html")) {
            if (user == null) {
                redirect(ex, "/admin-login.html");
                return;
            }
            if (user.getRole() != Role.ADMIN) {
                sendError(ex, 403, "Access denied: Administrator privileges required to access the admin dashboard.");
                return;
            }
        }

        // 3. Admin script protection (/script.js) - prevent students from loading admin control scripts
        if (path.equals("/script.js")) {
            if (user != null && user.getRole() != Role.ADMIN) {
                sendError(ex, 403, "Access denied: Administrator resources are restricted.");
                return;
            }
        }

        // 4. Student dashboard route (/student.html) - strictly STUDENT only
        if (path.equals("/student.html")) {
            if (user == null) {
                redirect(ex, "/student-login.html");
                return;
            }
            if (user.getRole() != Role.STUDENT) {
                redirect(ex, "/index.html");
                return;
            }
            Path studentFile = FRONTEND.resolve("student.html").normalize();
            if (!Files.isRegularFile(studentFile)) {
                // Graceful fallback while student dashboard is under development
                String html = "<!doctype html><html><head><meta charset='utf-8'><title>EduBridge | Student Portal</title>"
                        + "<link rel='stylesheet' href='/style.css'></head><body style='display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;'>"
                        + "<div style='text-align:center;padding:48px;background:#fff;border-radius:16px;box-shadow:0 10px 30px rgba(0,0,0,0.08);max-width:440px;'>"
                        + "<h2 style='margin-top:0;color:#292640;'>Student Portal</h2>"
                        + "<p style='color:#666;'>Welcome, <strong>" + escapeHtml(user.getFullName()) + "</strong>.</p>"
                        + "<p style='font-size:12px;color:#89869f;'>Role: STUDENT | Student ID: " + (user.getStudentId() == null ? "Not linked" : user.getStudentId()) + "</p>"
                        + "<button onclick='fetch(\"/api/auth/logout\",{method:\"POST\"}).then(()=>location.href=\"/student-login.html\");' class='primary-button' style='margin-top:16px;'>Sign out</button>"
                        + "</div></body></html>";
                sendText(ex, 200, "text/html; charset=utf-8", html);
                return;
            }
        }

        // 5. Admin login route (/admin-login.html)
        if (path.equals("/admin-login.html") && user != null) {
            redirect(ex, user.getRole() == Role.ADMIN ? "/index.html" : "/student.html");
            return;
        }

        // 6. Student login route (/student-login.html)
        if (path.equals("/student-login.html") && user != null) {
            redirect(ex, user.getRole() == Role.STUDENT ? "/student.html" : "/index.html");
            return;
        }

        // 7. Legacy /login.html route redirects directly to /student-login.html
        if (path.equals("/login.html")) {
            if (user == null) {
                redirect(ex, "/student-login.html");
                return;
            }
            redirect(ex, user.getRole() == Role.ADMIN ? "/index.html" : "/student.html");
            return;
        }

        // 8. General static file resolution (CSS, JS, images, sample PDFs)
        Path file = FRONTEND.resolve(path.startsWith("/") ? path.substring(1) : path).normalize();

        // Security: never serve files outside the frontend folder.
        if (!file.startsWith(FRONTEND) || !Files.isRegularFile(file)) {
            sendText(ex, 404, "text/plain; charset=utf-8", "Not found");
            return;
        }

        // Secondary check: prevent direct file loading of index.html by non-admins
        if (file.getFileName().toString().equals("index.html")) {
            if (user == null) { redirect(ex, "/admin-login.html"); return; }
            if (user.getRole() != Role.ADMIN) {
                sendError(ex, 403, "Access denied: Administrator privileges required.");
                return;
            }
        }

        // Secondary check: prevent direct file loading of script.js by students
        if (file.getFileName().toString().equals("script.js")) {
            if (user != null && user.getRole() != Role.ADMIN) {
                sendError(ex, 403, "Access denied: Administrator resources are restricted.");
                return;
            }
        }

        // Secondary check: student.html redirect if anonymous
        if (file.getFileName().toString().equals("student.html")) {
            if (user == null) { redirect(ex, "/student-login.html"); return; }
            if (user.getRole() != Role.STUDENT) { redirect(ex, "/index.html"); return; }
        }

        String name = file.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "application/javascript; charset=utf-8"
                : name.endsWith(".png") ? "image/png"
                : name.endsWith(".svg") ? "image/svg+xml"
                : "application/octet-stream";
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ===================== SECURITY & HELPERS =====================

    private static boolean isCookieSecure(HttpExchange ex) {
        String env = System.getenv("COOKIE_SECURE");
        if ("true".equalsIgnoreCase(env)) return true;
        if ("false".equalsIgnoreCase(env)) return false;
        if (ex != null) {
            String proto = ex.getRequestHeaders().getFirst("X-Forwarded-Proto");
            if ("https".equalsIgnoreCase(proto)) return true;
            String forwarded = ex.getRequestHeaders().getFirst("Forwarded");
            if (forwarded != null && forwarded.toLowerCase().contains("proto=https")) return true;
        }
        return false;
    }

    static boolean isCookieSecure() {
        return isCookieSecure(null);
    }

    private static String sessionCookieHeader(HttpExchange ex, String token) {
        return SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax"
                + (isCookieSecure(ex) ? "; Secure" : "");
    }

    static String sessionCookieHeader(String token) {
        return sessionCookieHeader(null, token);
    }

    private static String expiredCookieHeader(HttpExchange ex) {
        return SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
                + (isCookieSecure(ex) ? "; Secure" : "");
    }

    static String expiredCookieHeader() {
        return expiredCookieHeader(null);
    }

    // Defense-in-depth CSRF verification for state-changing requests
    private static boolean isSameOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank()) {
            String referer = ex.getRequestHeaders().getFirst("Referer");
            if (referer == null || referer.isBlank()) return true;
            origin = referer;
        }
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || host.isBlank()) return true;
        try {
            java.net.URI uri = java.net.URI.create(origin);
            String originHost = uri.getHost();
            if (originHost == null) return false;
            String expectedHost = host.contains(":") ? host.split(":", 2)[0] : host;
            return originHost.equalsIgnoreCase(expectedHost);
        } catch (Exception e) {
            return false;
        }
    }

    // Reads the session token from the "Cookie" header.
    private static String sessionToken(HttpExchange ex) {
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : header.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(SESSION_COOKIE) && !kv[1].isEmpty()) return kv[1];
            }
        }
        return null;
    }

    // Never exposes password hashes, salts, or session tokens.
    private static String userJson(UserAccount user) {
        return json(
                "userId", user.getUserId(),
                "fullName", user.getFullName(),
                "username", user.getUsername(),
                "email", user.getEmail(),
                "role", user.getRole().name(),
                "studentId", user.getStudentId()
        );
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private static Map<Integer, String> departmentNames() throws SQLException {
        Map<Integer, String> names = new HashMap<>();
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT department_id, department_name FROM departments");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) names.put(rs.getInt(1), rs.getString(2));
        }
        return names;
    }

    // Handles preflight OPTIONS requests without leaking wildcard cross-origin access.
    // Prefers same-origin requests.
    private static boolean handleOptions(HttpExchange ex) throws IOException {
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "GET, POST, OPTIONS");
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return true;
        }
        return false;
    }

    // Reads form or JSON data sent by client, augmented with query parameters
    private static Map<String, String> readForm(HttpExchange ex) throws IOException {
        Map<String, String> values = new HashMap<>();
        if (ex.getRequestURI().getRawQuery() != null) {
            values.putAll(parseQuery(ex.getRequestURI().getRawQuery()));
        }
        try (InputStream in = ex.getRequestBody()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (body.startsWith("{") && body.endsWith("}")) {
                values.putAll(parseSimpleJson(body));
            } else if (!body.isEmpty()) {
                values.putAll(parseQuery(body));
            }
        }
        return values;
    }

    private static Map<String, String> parseSimpleJson(String json) {
        Map<String, String> map = new HashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*(?:\"([^\"]*)\"|([0-9]+(?:\\.[0-9]+)?|true|false|null))").matcher(json);
        while (m.find()) {
            String key = m.group(1);
            String val = m.group(2) != null ? m.group(2) : m.group(3);
            map.put(key, "null".equalsIgnoreCase(val) ? "" : val);
        }
        return map;
    }

    private static Map<String, String> parseQuery(String text) {
        Map<String, String> values = new HashMap<>();
        if (text == null || text.isEmpty()) return values;
        for (String pair : text.split("&")) {
            int i = pair.indexOf('=');
            String key = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String value = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return values;
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // Builds a JSON object from key/value pairs: json("a", 1, "b", "x") -> {"a":1,"b":"x"}
    private static String json(Object... keyValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append(quote(String.valueOf(keyValues[i]))).append(':');
            Object value = keyValues[i + 1];
            if (value == null) sb.append("null");
            else if (value instanceof Number || value instanceof Boolean) sb.append(value);
            else sb.append(quote(value.toString()));
        }
        return sb.append('}').toString();
    }

    private static String array(List<String> items) {
        return "[" + String.join(",", items) + "]";
    }

    private static String quote(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        sendText(ex, status, "application/json; charset=utf-8", body);
    }

    private static void sendError(HttpExchange ex, int status, String message) throws IOException {
        send(ex, status, json("message", message));
    }

    private static void sendText(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
