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
   WEB SERVER FOR THE BROWSER DASHBOARD (NEW FILE)
   - Main.java (console menu) is NOT changed. This class only REUSES
     StudentManager, DBConnection, Student and Course from Main.java.
   - Uses the JDK built-in HttpServer, so no Spring Boot and no new library.
   - Serves the dashboard from the "frontend" folder and JSON data at /api/...

   - Login / sign-up (AuthManager.java) and attendance (AttendanceManager.java)
     create their own tables when the server starts.

   Run (from the project folder):
     mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt
     java -cp "target/classes:$(cat cp.txt)" com.studentmanagement.WebServer
   Then open: http://localhost:8080  (you will see the login page first)
   ================================================================ */
public class WebServer {

    // UNIT II: static members shared by the whole server.
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    private static final Path FRONTEND = Paths.get(System.getenv().getOrDefault("FRONTEND_DIR", "frontend"))
            .toAbsolutePath().normalize();

    // UNIT II: object of the existing StudentManager class (association / message passing).
    private static final StudentManager manager = new StudentManager();
    private static final AuthManager auth = new AuthManager();
    private static final AttendanceManager attendance = new AttendanceManager();
    private static final String SESSION_COOKIE = "CFSESSION";

    public static void main(String[] args) throws IOException {
        // Create the new tables (users, attendance) if they are missing.
        try {
            auth.createTable();
            attendance.createTable();
            try (Connection con = DBConnection.getConnection(); Statement st = con.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS department_grades (student_id INT NOT NULL, department_id INT NOT NULL, marks DECIMAL(5,2) NOT NULL, PRIMARY KEY(student_id,department_id), FOREIGN KEY(student_id) REFERENCES students(student_id), FOREIGN KEY(department_id) REFERENCES departments(department_id), CHECK(marks >= 0 AND marks <= 100))");
            }
        } catch (SQLException e) {
            System.out.println("Could not create users/attendance tables: " + e.getMessage());
            System.out.println("Check DB_PASSWORD and that sql/schema.sql was loaded.");
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Login / sign-up routes (open to everyone)
        server.createContext("/api/auth/signup", WebServer::signup);
        server.createContext("/api/auth/login", WebServer::login);
        server.createContext("/api/auth/logout", WebServer::logout);
        server.createContext("/api/auth/me", WebServer::me);

        // API routes (message passing: browser -> WebServer -> StudentManager -> MySQL via JDBC)
        // secured(...) = only a logged-in user can use them.
        server.createContext("/api/departments", secured(WebServer::departments));
        server.createContext("/api/students", secured(WebServer::students));
        server.createContext("/api/courses", secured(WebServer::courses));
        server.createContext("/api/enrollments", secured(WebServer::registrations));
        server.createContext("/api/registrations", secured(WebServer::registrations));
        server.createContext("/api/grades", secured(WebServer::grades));
        server.createContext("/api/gpa", secured(WebServer::gpa));
        server.createContext("/api/attendance", secured(WebServer::attendance));
        server.createContext("/", WebServer::staticFile);

        server.setExecutor(null); // default single-threaded executor: one request at a time
        server.start();
        System.out.println("CampusFlow dashboard running at http://localhost:" + PORT);
        System.out.println("Serving frontend from: " + FRONTEND);
    }

    // ===================== LOGIN / SIGN-UP =====================

    // POST /api/auth/signup  (fullName, username, email, password)
    private static void signup(HttpExchange ex) throws IOException {
        if (handleOptions(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendError(ex, 405, "Use POST."); return; }
        Map<String, String> form = readForm(ex);
        try {
            auth.signup(form.getOrDefault("fullName", "").trim(), form.getOrDefault("username", "").trim(),
                    form.getOrDefault("email", "").trim().toLowerCase(), form.getOrDefault("password", ""));
            send(ex, 201, json("message", "Account created. Please sign in."));
        } catch (InvalidSignupException e) {
            sendError(ex, 400, e.getMessage());
        } catch (DuplicateUserException e) {
            sendError(ex, 409, e.getMessage());
        } catch (SQLException e) {
            sendError(ex, 500, "Database error: " + e.getMessage());
        } finally {
            System.out.println("Sign-up attempt completed (web).");
        }
    }

    // POST /api/auth/login  (username = username or email, password)
    private static void login(HttpExchange ex) throws IOException {
        if (handleOptions(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendError(ex, 405, "Use POST."); return; }
        Map<String, String> form = readForm(ex);
        try {
            UserAccount user = auth.login(form.getOrDefault("username", "").trim(), form.getOrDefault("password", ""));
            String token = auth.createSession(user);
            ex.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax");
            send(ex, 200, userJson(user));
        } catch (InvalidLoginException e) {
            sendError(ex, 401, e.getMessage());
        } catch (SQLException e) {
            sendError(ex, 500, "Database error: " + e.getMessage());
        } finally {
            System.out.println("Login attempt completed (web).");
        }
    }

    // POST /api/auth/logout
    private static void logout(HttpExchange ex) throws IOException {
        if (handleOptions(ex)) return;
        auth.logout(sessionToken(ex));
        ex.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        send(ex, 200, json("message", "Logged out."));
    }

    // GET /api/auth/me — who is logged in?
    private static void me(HttpExchange ex) throws IOException {
        if (handleOptions(ex)) return;
        UserAccount user = auth.getUser(sessionToken(ex));
        if (user == null) sendError(ex, 401, "Not logged in.");
        else send(ex, 200, userJson(user));
    }

    // ===================== API HANDLERS =====================

    // GET /api/departments and POST /api/departments
    private static void departments(HttpExchange ex) throws IOException {
        if (handleOptions(ex)) return;
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
            if (!"GET".equals(ex.getRequestMethod())) {
                sendError(ex, 405, "Use GET or POST.");
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
            sendError(ex, 500, "Database error: " + e.getMessage());
        }
    }

    // GET /api/students   |   POST /api/students
    private static void students(HttpExchange ex) throws IOException {
        if (handleOptions(ex)) return;
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
            sendError(ex, 500, "Database error: " + e.getMessage());
        }
    }

    // GET /api/courses — course details plus confirmed / waitlisted counts
    private static void courses(HttpExchange ex) throws IOException {
        if (handleOptions(ex)) return;
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
            sendError(ex, 500, "Database error: " + e.getMessage());
        }
    }

    // GET /api/registrations   |   POST /api/registrations
    private static void registrations(HttpExchange ex) throws IOException {
        if (handleOptions(ex)) return;
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
                        try (PreparedStatement ps = con.prepareStatement("SELECT c.capacity, (SELECT COUNT(*) FROM registrations r WHERE r.course_code=c.course_code AND r.status='CONFIRMED') FROM courses c WHERE c.course_code=?")) {
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
                sendError(ex, 500, "Database error: " + e.getMessage());
            } finally {
                // UNIT IV: finally always executes.
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
            sendError(ex, 500, "Database error: " + e.getMessage());
        }
    }

    // GET /api/grades | POST /api/grades — department-based marks for the dashboard.
    private static void grades(HttpExchange ex) throws IOException {
        if (handleOptions(ex)) return;
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
            } catch (NumberFormatException e) { sendError(ex, 400, "Select a student and department and enter valid marks."); }
              catch (SQLException e) { sendError(ex, 500, "Database error: " + e.getMessage()); }
            return;
        }
        String sql = "SELECT g.student_id,s.student_name,g.department_id,d.department_name,g.marks FROM department_grades g JOIN students s ON s.student_id=g.student_id JOIN departments d ON d.department_id=g.department_id ORDER BY g.student_id,g.department_id";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            List<String> rows = new ArrayList<>();
            while (rs.next()) rows.add(json("studentId", rs.getInt(1), "studentName", rs.getString(2), "departmentId", rs.getInt(3), "departmentName", rs.getString(4), "marks", rs.getDouble(5)));
            send(ex, 200, array(rows));
        } catch (SQLException e) { sendError(ex, 500, "Database error: " + e.getMessage()); }
    }

    private static double calculateDepartmentGpa(int studentId) throws SQLException {
        String sql = "SELECT AVG(marks) FROM department_grades WHERE student_id=?";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId); try (ResultSet rs = ps.executeQuery()) { rs.next(); double avg = rs.getDouble(1); return rs.wasNull() ? 0.0 : Math.round(avg * 10.0) / 100.0; }
        }
    }

    private static void gpa(HttpExchange ex) throws IOException {
        if (handleOptions(ex)) return;
        try {
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
            int studentId = Integer.parseInt(query.getOrDefault("studentId", "").trim());
            send(ex, 200, json("studentId", studentId, "gpa", calculateDepartmentGpa(studentId)));
        } catch (NumberFormatException e) { sendError(ex, 400, "Select a valid student."); }
          catch (SQLException e) { sendError(ex, 500, "Database error: " + e.getMessage()); }
    }

    // Attendance form accepts one student, date, course and status per submission.
    private static void attendance(HttpExchange ex) throws IOException {
        if (handleOptions(ex)) return;
        if ("POST".equals(ex.getRequestMethod())) {
            Map<String, String> form = readForm(ex);
            try {
                int studentId = Integer.parseInt(form.getOrDefault("studentId", "").trim());
                String courseCode = form.getOrDefault("courseCode", "").trim().toUpperCase();
                attendance.markAttendance(studentId, courseCode, form.getOrDefault("date", ""), form.getOrDefault("status", ""));
                send(ex, 201, json("message", "Attendance saved."));
            } catch (InvalidAttendanceException e) { sendError(ex, 400, "Attendance error: " + e.getMessage()); }
              catch (NumberFormatException e) { sendError(ex, 400, "Select a valid student."); }
              catch (SQLException e) { sendError(ex, 500, "Database error: " + e.getMessage()); }
            return;
        }
        try {
            List<String> rows = new ArrayList<>();
            for (AttendanceRecord a : attendance.getAll()) rows.add(json("attendanceId", a.getAttendanceId(), "studentId", a.getStudentId(), "studentName", a.getStudentName(), "courseCode", a.getCourseCode(), "courseName", a.getCourseName(), "date", a.getAttendanceDate(), "status", a.getStatus()));
            send(ex, 200, array(rows));
        } catch (SQLException e) { sendError(ex, 500, "Database error: " + e.getMessage()); }
    }

    // ===================== STATIC FRONTEND FILES =====================

    private static void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";

        // Dashboard needs login; the login page is skipped when already logged in.
        boolean loggedIn = auth.getUser(sessionToken(ex)) != null;
        if (path.equals("/index.html") && !loggedIn) { redirect(ex, "/login.html"); return; }
        if (path.equals("/login.html") && loggedIn) { redirect(ex, "/"); return; }
        Path file = FRONTEND.resolve(path.substring(1)).normalize();

        // Security: never serve files outside the frontend folder.
        if (!file.startsWith(FRONTEND) || !Files.isRegularFile(file)) {
            sendText(ex, 404, "text/plain; charset=utf-8", "Not found");
            return;
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
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ===================== HELPERS =====================

    // Wraps an API handler so it only runs for a logged-in user.
    private static HttpHandler secured(HttpHandler handler) {
        return ex -> {
            if (handleOptions(ex)) return;
            if (auth.getUser(sessionToken(ex)) == null) {
                sendError(ex, 401, "Please log in first.");
                return;
            }
            handler.handle(ex);
        };
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

    private static String userJson(UserAccount user) {
        return json("userId", user.getUserId(), "fullName", user.getFullName(),
                "username", user.getUsername(), "email", user.getEmail());
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

    // Allows the page to be opened directly as a file (file://) and still call the API.
    private static boolean handleOptions(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return true;
        }
        return false;
    }

    // Reads "a=1&b=2" (form data) sent by the browser.
    private static Map<String, String> readForm(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return parseQuery(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
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
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
