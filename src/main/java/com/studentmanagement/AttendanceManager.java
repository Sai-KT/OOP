package com.studentmanagement;

import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/* ================================================================
   ATTENDANCE (NEW FILE)
   - Main.java and schema.sql are NOT changed.
   - The "attendance" table is created automatically (CREATE TABLE IF NOT EXISTS)
     when WebServer starts.
   ================================================================ */

// UNIT IV: User-defined exception for invalid attendance entries.
class InvalidAttendanceException extends Exception {
    public InvalidAttendanceException(String message) { super(message); }
}

// UNIT II: Attendance record object with private attributes (encapsulation).
class AttendanceRecord {
    private final int attendanceId;
    private final int studentId;
    private final String studentName;
    private final String courseCode;
    private final String courseName;
    private final String attendanceDate;
    private final String status;

    public AttendanceRecord(int attendanceId, int studentId, String studentName, String courseCode,
                            String courseName, String attendanceDate, String status) {
        this.attendanceId = attendanceId; this.studentId = studentId; this.studentName = studentName;
        this.courseCode = courseCode; this.courseName = courseName;
        this.attendanceDate = attendanceDate; this.status = status;
    }
    public int getAttendanceId() { return attendanceId; }
    public int getStudentId() { return studentId; }
    public String getStudentName() { return studentName; }
    public String getCourseCode() { return courseCode; }
    public String getCourseName() { return courseName; }
    public String getAttendanceDate() { return attendanceDate; }
    public String getStatus() { return status; }
}

class AttendanceManager {

    // JDBC: creates the attendance table only if it does not exist yet.
    public void createTable() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS attendance ("
                + "attendance_id INT AUTO_INCREMENT PRIMARY KEY, "
                + "student_id INT NOT NULL, "
                + "course_code VARCHAR(20) NOT NULL, "
                + "attendance_date DATE NOT NULL, "
                + "status VARCHAR(10) NOT NULL, "
                + "UNIQUE KEY uq_attendance (student_id, course_code, attendance_date), "
                + "FOREIGN KEY (student_id) REFERENCES students(student_id), "
                + "FOREIGN KEY (course_code) REFERENCES courses(course_code))";
        try (Connection con = DBConnection.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    // Mark (or update) one student's attendance for one course on one date.
    public void markAttendance(int studentId, String courseCode, String date, String status)
            throws SQLException, InvalidAttendanceException {
        status = status.trim().toUpperCase();
        if (!status.equals("PRESENT") && !status.equals("ABSENT") && !status.equals("LATE"))
            throw new InvalidAttendanceException("Status must be PRESENT, ABSENT or LATE.");

        LocalDate day;
        try {
            day = LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidAttendanceException("Date must be in YYYY-MM-DD format.");
        }
        if (day.isAfter(LocalDate.now())) throw new InvalidAttendanceException("Attendance cannot be marked for a future date.");

        // Attendance can be recorded for any existing student/course pair; enrollment grade/prerequisite is not required.
        String sql = "INSERT INTO attendance(student_id, course_code, attendance_date, status) VALUES(?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE status=VALUES(status)";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId); ps.setString(2, courseCode);
            ps.setDate(3, Date.valueOf(day)); ps.setString(4, status);
            ps.executeUpdate();
        }
    }

    // JDBC JOIN: attendance with student and course names (generic List<AttendanceRecord>).
    public List<AttendanceRecord> getAll() throws SQLException {
        String sql = "SELECT a.attendance_id, a.student_id, s.student_name, a.course_code, c.course_name, "
                + "a.attendance_date, a.status FROM attendance a "
                + "JOIN students s ON s.student_id = a.student_id "
                + "JOIN courses c ON c.course_code = a.course_code "
                + "ORDER BY a.attendance_date DESC, a.course_code, a.student_id";
        List<AttendanceRecord> list = new ArrayList<>();
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new AttendanceRecord(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getDate(6).toString(), rs.getString(7)));
            }
        }
        return list;
    }
}
