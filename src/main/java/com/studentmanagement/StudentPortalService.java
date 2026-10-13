package com.studentmanagement;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/* ================================================================
   STUDENT PORTAL SERVICE (PHASE 4)
   - Secure data access layer for authenticated STUDENT accounts.
   - All queries strictly enforce studentId filtering obtained from the
     authenticated UserAccount session.
   - Read-only: no modification operations exist.
   ================================================================ */
public class StudentPortalService {

    // 1. Personal Profile (/api/student/me)
    public String getProfile(int studentId) throws SQLException {
        String sql = "SELECT s.student_id, s.student_name, s.email, s.semester, s.student_type, "
                + "s.department_id, d.department_name "
                + "FROM students s "
                + "LEFT JOIN departments d ON s.department_id = d.department_id "
                + "WHERE s.student_id = ?";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String type = rs.getString("student_type");
                String guidance = "POSTGRADUATE".equalsIgnoreCase(type)
                        ? "Postgraduate: check the advanced-course prerequisites and credit requirements."
                        : "Undergraduate: complete the listed prerequisite course before enrolling.";
                double gpa = getGpa(studentId);
                return json(
                        "studentId", rs.getInt("student_id"),
                        "studentName", rs.getString("student_name"),
                        "email", rs.getString("email"),
                        "semester", rs.getInt("semester"),
                        "departmentId", rs.getInt("department_id"),
                        "departmentName", rs.getString("department_name") == null ? "" : rs.getString("department_name"),
                        "studentType", type,
                        "guidance", guidance,
                        "gpa", gpa
                );
            }
        }
    }

    // 2. Enrolled Courses (/api/student/courses)
    public String getCourses(int studentId) throws SQLException {
        String sql = "SELECT c.course_code, c.course_name, c.credits, c.capacity, c.prerequisite_code, "
                + "r.status, r.registration_date "
                + "FROM registrations r "
                + "JOIN courses c ON r.course_code = c.course_code "
                + "WHERE r.student_id = ? "
                + "ORDER BY c.course_code";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> rows = new ArrayList<>();
                while (rs.next()) {
                    Timestamp date = rs.getTimestamp("registration_date");
                    rows.add(json(
                            "courseCode", rs.getString("course_code"),
                            "courseName", rs.getString("course_name"),
                            "credits", rs.getInt("credits"),
                            "capacity", rs.getInt("capacity"),
                            "prerequisiteCode", rs.getString("prerequisite_code"),
                            "status", rs.getString("status"),
                            "registrationDate", date == null ? null : date.toString()
                    ));
                }
                return array(rows);
            }
        }
    }

    // 3. Registrations (/api/student/registrations)
    public String getRegistrations(int studentId) throws SQLException {
        String sql = "SELECT r.registration_id, r.course_code, c.course_name, c.credits, "
                + "r.status, r.registration_date "
                + "FROM registrations r "
                + "JOIN courses c ON r.course_code = c.course_code "
                + "WHERE r.student_id = ? "
                + "ORDER BY r.registration_id";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> rows = new ArrayList<>();
                while (rs.next()) {
                    Timestamp date = rs.getTimestamp("registration_date");
                    rows.add(json(
                            "registrationId", rs.getInt("registration_id"),
                            "courseCode", rs.getString("course_code"),
                            "courseName", rs.getString("course_name"),
                            "credits", rs.getInt("credits"),
                            "status", rs.getString("status"),
                            "registrationDate", date == null ? null : date.toString()
                    ));
                }
                return array(rows);
            }
        }
    }

    // 4. Attendance (/api/student/attendance)
    public String getAttendance(int studentId) throws SQLException {
        String sql = "SELECT a.attendance_id, a.course_code, c.course_name, a.attendance_date, a.status "
                + "FROM attendance a "
                + "JOIN courses c ON a.course_code = c.course_code "
                + "WHERE a.student_id = ? "
                + "ORDER BY a.attendance_date DESC, a.course_code";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(json(
                            "attendanceId", rs.getInt("attendance_id"),
                            "courseCode", rs.getString("course_code"),
                            "courseName", rs.getString("course_name"),
                            "date", rs.getDate("attendance_date").toString(),
                            "status", rs.getString("status")
                    ));
                }
                return array(rows);
            }
        }
    }

    // 5. Grades (/api/student/grades)
    public String getGrades(int studentId) throws SQLException {
        String sql = "SELECT g.department_id, d.department_name, g.marks "
                + "FROM department_grades g "
                + "JOIN departments d ON g.department_id = d.department_id "
                + "WHERE g.student_id = ? "
                + "ORDER BY g.department_id";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> rows = new ArrayList<>();
                while (rs.next()) {
                    double marks = rs.getDouble("marks");
                    rows.add(json(
                            "departmentId", rs.getInt("department_id"),
                            "departmentName", rs.getString("department_name"),
                            "marks", marks,
                            "passed", marks >= 40.0
                    ));
                }
                return array(rows);
            }
        }
    }

    // 6. GPA (/api/student/gpa) — departmental calculation matching existing formula
    public double getGpa(int studentId) throws SQLException {
        String sql = "SELECT AVG(marks) FROM department_grades WHERE student_id = ?";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0.0;
                double avg = rs.getDouble(1);
                return rs.wasNull() ? 0.0 : Math.round(avg * 10.0) / 100.0;
            }
        }
    }

    // ===================== JSON UTILITIES =====================

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
}
