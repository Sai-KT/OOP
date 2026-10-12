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
   LOGIN AND SIGN-UP (NEW FILE)
   - Main.java and schema.sql are NOT changed.
   - The "users" table is created automatically (CREATE TABLE IF NOT EXISTS)
     when WebServer starts.
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

// UNIT II: Class with private attributes, constructor and getters (encapsulation).
class UserAccount {
    private final int userId;
    private final String fullName;
    private final String username;
    private final String email;

    public UserAccount(int userId, String fullName, String username, String email) {
        this.userId = userId;
        this.fullName = fullName;
        this.username = username;
        this.email = email;
    }
    public int getUserId() { return userId; }
    public String getFullName() { return fullName; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
}

// UNIT IV: JDBC + generic collection (Map<String, UserAccount>) for logged-in sessions.
class AuthManager {
    private final Map<String, UserAccount> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    // JDBC: creates the users table only if it does not exist yet.
    public void createTable() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS users ("
                + "user_id INT AUTO_INCREMENT PRIMARY KEY, "
                + "full_name VARCHAR(100) NOT NULL, "
                + "username VARCHAR(50) NOT NULL UNIQUE, "
                + "email VARCHAR(150) NOT NULL UNIQUE, "
                + "password_hash CHAR(64) NOT NULL, "
                + "salt CHAR(32) NOT NULL, "
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";
        try (Connection con = DBConnection.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    // SIGN UP: validate -> check duplicates -> save hashed password with PreparedStatement.
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
        String sql = "INSERT INTO users(full_name, username, email, password_hash, salt) VALUES(?,?,?,?,?)";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, fullName); ps.setString(2, username); ps.setString(3, email);
            ps.setString(4, hash(password, salt)); ps.setString(5, salt);
            ps.executeUpdate();
        }
    }

    // LOGIN: find user by username OR email, then compare password hashes.
    public UserAccount login(String usernameOrEmail, String password) throws SQLException, InvalidLoginException {
        String sql = "SELECT user_id, full_name, username, email, password_hash, salt FROM users WHERE username=? OR email=?";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, usernameOrEmail); ps.setString(2, usernameOrEmail);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new InvalidLoginException("Incorrect username/email or password.");
                byte[] expected = rs.getString("password_hash").getBytes(StandardCharsets.UTF_8);
                byte[] actual = hash(password, rs.getString("salt")).getBytes(StandardCharsets.UTF_8);
                if (!MessageDigest.isEqual(expected, actual))
                    throw new InvalidLoginException("Incorrect username/email or password.");
                return new UserAccount(rs.getInt("user_id"), rs.getString("full_name"),
                        rs.getString("username"), rs.getString("email"));
            }
        }
    }

    // Session handling: random token -> logged-in user (stored in a Map).
    public String createSession(UserAccount user) {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        sessions.put(token, user);
        return token;
    }
    public UserAccount getUser(String token) { return token == null ? null : sessions.get(token); }
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
