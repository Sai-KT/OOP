package com.studentmanagement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/* ================================================================
   LOGIN, SIGN-UP AND ROLE-BASED AUTHENTICATION
   - Unit II: Encapsulation (UserAccount with private fields & getters).
   - Unit IV: Custom exceptions (InvalidLoginException, InvalidSignupException, DuplicateUserException).
   - Unit IV: JDBC with PreparedStatement and Connection management.
   - Unit IV: Generic collection (ConcurrentHashMap<String, UserAccount>) for sessions.
   - Authoritative RBAC: checks database on session verification to prevent stale privilege retention.
   ================================================================ */

// UNIT IV: User-defined exception for wrong username/email or password.
class InvalidLoginException extends Exception {
    public InvalidLoginException(String message) { super(message); }
}
// UNIT IV: User-defined exception for invalid sign-up details.
class InvalidSignupException extends Exception {
    public InvalidSignupException(String message) { super(message); }
}
// UNIT IV: User-defined exception when the username or email is already taken.
class DuplicateUserException extends Exception {
    public DuplicateUserException(String message) { super(message); }
}

// UNIT II: Class with private attributes, constructors and getters demonstrating encapsulation.
class UserAccount {
    private final int userId;
    private final String fullName;
    private final String username;
    private final String email;
    private final Role role;
    private final Integer studentId;

    public UserAccount(int userId, String fullName, String username, String email, Role role, Integer studentId) {
        this.userId = userId;
        this.fullName = fullName;
        this.username = username;
        this.email = email;
        this.role = (role == null) ? Role.STUDENT : role;
        this.studentId = studentId;
    }

    // Overload for backward compatibility
    public UserAccount(int userId, String fullName, String username, String email) {
        this(userId, fullName, username, email, Role.STUDENT, null);
    }

    public int getUserId() { return userId; }
    public String getFullName() { return fullName; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public Role getRole() { return role; }
    public Integer getStudentId() { return studentId; }

    public boolean isAdmin() { return role == Role.ADMIN; }
    public boolean isStudent() { return role == Role.STUDENT; }
}

// UNIT IV: JDBC + generic collection (Map<String, UserAccount>) for logged-in sessions.
class AuthManager {
    private final Map<String, UserAccount> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    // JDBC: creates the users table with RBAC columns for completely new installations.
    public void createTable() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS users ("
                + "user_id INT AUTO_INCREMENT PRIMARY KEY, "
                + "full_name VARCHAR(100) NOT NULL, "
                + "username VARCHAR(50) NOT NULL UNIQUE, "
                + "email VARCHAR(150) NOT NULL UNIQUE, "
                + "role VARCHAR(20) NOT NULL DEFAULT 'STUDENT', "
                + "student_id INT NULL, "
                + "password_hash CHAR(64) NOT NULL, "
                + "salt CHAR(32) NOT NULL, "
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "CONSTRAINT chk_user_role CHECK (role IN ('ADMIN', 'STUDENT')), "
                + "CONSTRAINT uq_users_student_id UNIQUE (student_id), "
                + "CONSTRAINT fk_users_student FOREIGN KEY (student_id) REFERENCES students(student_id) ON DELETE SET NULL ON UPDATE CASCADE)";
        try (Connection con = DBConnection.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    // SIGN UP: All public sign-up accounts are strictly STUDENT accounts with student_id = NULL.
    // Never accepts role or studentId from client requests.
    public void signup(String fullName, String username, String email, String password)
            throws SQLException, InvalidSignupException, DuplicateUserException {
        if (fullName.isBlank()) throw new InvalidSignupException("Enter your full name.");
        if (!username.matches("[A-Za-z0-9_.-]{3,50}"))
            throw new InvalidSignupException("Username must be 3–50 letters, numbers, dot, underscore or hyphen.");
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new InvalidSignupException("Enter a valid email address.");
        if (password.length() < 8) throw new InvalidSignupException("Password must be at least 8 characters.");

        String checkSql = "SELECT username, email FROM users WHERE username=? OR email=?";
        try (Connection con = DBConnection.getConnection(); PreparedStatement check = con.prepareStatement(checkSql)) {
            check.setString(1, username); check.setString(2, email);
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) {
                    if (username.equalsIgnoreCase(rs.getString("username")))
                        throw new DuplicateUserException("This username is already taken.");
                    throw new DuplicateUserException("An account with this email already exists.");
                }
            }
        }

        String salt = HexFormat.of().formatHex(randomBytes(16));
        String sql = "INSERT INTO users(full_name, username, email, password_hash, salt, role, student_id) VALUES(?,?,?,?,?,?,NULL)";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, fullName);
            ps.setString(2, username);
            ps.setString(3, email);
            ps.setString(4, hash(password, salt));
            ps.setString(5, salt);
            ps.setString(6, Role.STUDENT.name());
            ps.executeUpdate();
        }
    }

    // LOGIN: find user by username OR email, verify password hash, and construct UserAccount with actual role.
    public UserAccount login(String usernameOrEmail, String password) throws SQLException, InvalidLoginException {
        String sql = "SELECT user_id, full_name, username, email, password_hash, salt, role, student_id FROM users WHERE username=? OR email=?";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, usernameOrEmail); ps.setString(2, usernameOrEmail);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new InvalidLoginException("Incorrect username/email or password.");
                byte[] expected = rs.getString("password_hash").getBytes(StandardCharsets.UTF_8);
                byte[] actual = hash(password, rs.getString("salt")).getBytes(StandardCharsets.UTF_8);
                if (!MessageDigest.isEqual(expected, actual))
                    throw new InvalidLoginException("Incorrect username/email or password.");

                int sid = rs.getInt("student_id");
                Integer studentId = rs.wasNull() ? null : sid;
                Role role = Role.fromString(rs.getString("role"));

                return new UserAccount(
                        rs.getInt("user_id"),
                        rs.getString("full_name"),
                        rs.getString("username"),
                        rs.getString("email"),
                        role,
                        studentId
                );
            }
        }
    }

    // INITIAL ADMIN PROVISIONING:
    // Creates ONE initial ADMIN account using environment variables if no admin exists.
    // Environment variables:
    //   CAMPUSFLOW_ADMIN_NAME (optional, defaults to "CampusFlow Administrator")
    //   CAMPUSFLOW_ADMIN_USERNAME (required)
    //   CAMPUSFLOW_ADMIN_EMAIL (required)
    //   CAMPUSFLOW_ADMIN_PASSWORD (required, >= 8 chars)
    public void bootstrapAdmin() throws SQLException {
        String adminPassword = System.getenv("CAMPUSFLOW_ADMIN_PASSWORD");
        String adminUsername = System.getenv("CAMPUSFLOW_ADMIN_USERNAME");
        String adminEmail = System.getenv("CAMPUSFLOW_ADMIN_EMAIL");
        String adminName = System.getenv().getOrDefault("CAMPUSFLOW_ADMIN_NAME", "CampusFlow Administrator").trim();

        // If credentials are not configured in environment, do nothing
        if (adminPassword == null || adminPassword.isBlank() ||
            adminUsername == null || adminUsername.isBlank() ||
            adminEmail == null || adminEmail.isBlank()) {
            return;
        }

        adminUsername = adminUsername.trim();
        adminEmail = adminEmail.trim().toLowerCase();

        try (Connection con = DBConnection.getConnection()) {
            // Check 1: Ensure no ADMIN account exists in the database.
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'")) {
                if (rs.next() && rs.getInt(1) > 0) {
                    // An admin account already exists. Never create a duplicate or overwrite.
                    return;
                }
            }

            // Check 2: Never automatically promote an existing account.
            // If the configured username/email already belongs to an existing account, stop with a warning.
            String checkSql = "SELECT user_id, username, email FROM users WHERE username = ? OR email = ?";
            try (PreparedStatement check = con.prepareStatement(checkSql)) {
                check.setString(1, adminUsername);
                check.setString(2, adminEmail);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        System.err.println("SECURITY ALERT: Cannot bootstrap administrator. The username '"
                                + adminUsername + "' or email '" + adminEmail + "' already belongs to an existing account.");
                        System.err.println("Administrator account was NOT created. Please choose a unique username and email.");
                        return;
                    }
                }
            }

            if (adminPassword.length() < 8) {
                System.err.println("SECURITY ALERT: CAMPUSFLOW_ADMIN_PASSWORD must be at least 8 characters long.");
                return;
            }

            // Provision the initial administrator account
            String salt = HexFormat.of().formatHex(randomBytes(16));
            String insertSql = "INSERT INTO users(full_name, username, email, password_hash, salt, role, student_id) VALUES(?,?,?,?,?,?,NULL)";
            try (PreparedStatement ps = con.prepareStatement(insertSql)) {
                ps.setString(1, adminName);
                ps.setString(2, adminUsername);
                ps.setString(3, adminEmail);
                ps.setString(4, hash(adminPassword, salt));
                ps.setString(5, salt);
                ps.setString(6, Role.ADMIN.name());
                ps.executeUpdate();
                System.out.println("CampusFlow: Initial administrator account created successfully: " + adminUsername);
                System.out.println("Security Notice: Remove CAMPUSFLOW_ADMIN_PASSWORD from environment once initialized.");
            }
        }
    }

    // Session handling: random token -> logged-in user (stored in a ConcurrentHashMap).
    public String createSession(UserAccount user) {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        sessions.put(token, user);
        return token;
    }

    // Immediately updates any active in-memory session for the given userId
    public void refreshUserStudentLink(int userId, Integer newStudentId) {
        for (Map.Entry<String, UserAccount> entry : sessions.entrySet()) {
            UserAccount account = entry.getValue();
            if (account != null && account.getUserId() == userId) {
                UserAccount updated = new UserAccount(
                        account.getUserId(),
                        account.getFullName(),
                        account.getUsername(),
                        account.getEmail(),
                        account.getRole(),
                        newStudentId
                );
                sessions.put(entry.getKey(), updated);
            }
        }
    }

    // Authoritative session resolution:
    // Validates token against memory, then checks current state from MySQL.
    // If a user was deleted or their role changed, this ensures the session does not retain stale privileges.
    public UserAccount getUser(String token) {
        if (token == null) return null;
        UserAccount cached = sessions.get(token);
        if (cached == null) return null;

        String sql = "SELECT user_id, full_name, username, email, role, student_id FROM users WHERE user_id = ?";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, cached.getUserId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // Account was deleted from database: invalidate session immediately
                    sessions.remove(token);
                    return null;
                }
                int sid = rs.getInt("student_id");
                Integer studentId = rs.wasNull() ? null : sid;
                Role authoritativeRole = Role.fromString(rs.getString("role"));

                // If role, student link, or name was updated in database, refresh the cached UserAccount
                UserAccount fresh = new UserAccount(
                        rs.getInt("user_id"),
                        rs.getString("full_name"),
                        rs.getString("username"),
                        rs.getString("email"),
                        authoritativeRole,
                        studentId
                );
                sessions.put(token, fresh);
                return fresh;
            }
        } catch (SQLException e) {
            // In case of transient database connection issue, fallback to cached session
            return cached;
        }
    }

    public void logout(String token) { if (token != null) sessions.remove(token); }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    // Passwords are never stored as plain text: SHA-256(salt + password).
    private static String hash(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((salt + password).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
