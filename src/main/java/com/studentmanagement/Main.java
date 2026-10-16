package com.studentmanagement;

import java.sql.*;
import java.util.*;

/* ================================================================
   STUDENT COURSE REGISTRATION AND MANAGEMENT SYSTEM — JDBC VERSION
   Based on the supplied OOP notes and Case Studies 1 / Group 06.
   Database access uses JDBC directly; Spring Boot is NOT required.
   ================================================================ */

// UNIT IV: User-defined exception for invalid registrations.
class InvalidRegistrationException extends Exception {
    public InvalidRegistrationException(String message) { super(message); }
}
// UNIT IV: User-defined exception for prerequisite enforcement.
class PrerequisiteNotMetException extends Exception {
    public PrerequisiteNotMetException(String message) { super(message); }
}
// UNIT IV: User-defined exception for course capacity and waitlist.
class CourseFullException extends Exception {
    public CourseFullException(String message) { super(message); }
}
// UNIT IV: User-defined exception for invalid grade values.
class InvalidGradeException extends Exception {
    public InvalidGradeException(String message) { super(message); }
}

// UNIT II: Class, private attributes, constructors, getters (encapsulation).
class Department {
    private final int departmentId;
    private String departmentName;
    public Department(int departmentId, String departmentName) {
        this.departmentId = departmentId;
        this.departmentName = departmentName;
    }
    public int getDepartmentId() { return departmentId; }
    public String getDepartmentName() { return departmentName; }
    public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }
}

// UNIT III: Parent/base class; state + behavior; static member.
class Student {
    private final int studentId;
    private String studentName;
    private String email;
    private int semester;
    private final int departmentId;
    private final String studentType;
    public static int totalStudents = 0; // UNIT II: static member shared by all Student objects.

    public Student(int studentId, String studentName, String email, int semester,
                   int departmentId, String studentType) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.email = email;
        this.semester = semester;
        this.departmentId = departmentId;
        this.studentType = studentType;
        totalStudents++;
    }
    public int getStudentId() { return studentId; }
    public String getStudentName() { return studentName; }
    public String getEmail() { return email; }
    public int getSemester() { return semester; }
    public int getDepartmentId() { return departmentId; }
    public String getStudentType() { return studentType; }
    public void setStudentName(String name) { studentName = name; }
    public void setEmail(String email) { this.email = email; }
    public void setSemester(int semester) { this.semester = semester; }

    // UNIT III: Method designed to be overridden (runtime polymorphism).
    public String getRegistrationGuidance() { return "Complete prerequisites before registration."; }
    public void displayStudent() {
        System.out.println("ID: " + studentId + " | Name: " + studentName + " | Email: " + email
                + " | Semester: " + semester + " | Type: " + studentType);
    }
}

// UNIT III: Inheritance (IS-A relationship) and method overriding.
class UndergraduateStudent extends Student {
    public UndergraduateStudent(int id, String name, String email, int semester, int departmentId) {
        super(id, name, email, semester, departmentId, "UNDERGRADUATE"); // constructor chaining
    }
    @Override public String getRegistrationGuidance() {
        return "Undergraduate: complete the listed prerequisite course before enrolling.";
    }
}
class PostgraduateStudent extends Student {
    public PostgraduateStudent(int id, String name, String email, int semester, int departmentId) {
        super(id, name, email, semester, departmentId, "POSTGRADUATE");
    }
    @Override public String getRegistrationGuidance() {
        return "Postgraduate: check the advanced-course prerequisites and credit requirements.";
    }
}

// UNIT II: Course class with private attributes, constructor, behavior and encapsulation.
class Course {
    private final String courseCode;
    private String courseName;
    private int credits;
    private int capacity;
    private String prerequisiteCode;
    public Course(String code, String name, int credits, int capacity, String prerequisiteCode) {
        this.courseCode = code; this.courseName = name; this.credits = credits;
        this.capacity = capacity; this.prerequisiteCode = prerequisiteCode;
    }
    public String getCourseCode() { return courseCode; }
    public String getCourseName() { return courseName; }
    public int getCredits() { return credits; }
    public int getCapacity() { return capacity; }
    public String getPrerequisiteCode() { return prerequisiteCode; }
    public void displayCourseDetails() {
        System.out.println(courseCode + " | " + courseName + " | Credits: " + credits
                + " | Capacity: " + capacity + " | Prerequisite: "
                + (prerequisiteCode == null ? "None" : prerequisiteCode));
    }
}

// UNIT II: Registration has Student and Course objects (association/object relationship).
class Registration {
    private final Student student;
    private final Course course;
    private String status;
    public Registration(Student student, Course course, String status) {
        this.student = student; this.course = course; this.status = status;
    }
    public Student getStudent() { return student; }
    public Course getCourse() { return course; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public void displayRegistration() {
        System.out.println("Student: " + student.getStudentName() + " (" + student.getStudentId()
                + ") | Course: " + course.getCourseName() + " (" + course.getCourseCode()
                + ") | Status: " + status);
    }
}


// UNIT II: Semester is a separate class/object identified in Case Study 1.
class Semester {
    private final int semesterNumber;
    private final String academicYear;
    public Semester(int semesterNumber, String academicYear) {
        this.semesterNumber = semesterNumber; this.academicYear = academicYear;
    }
    public int getSemesterNumber() { return semesterNumber; }
    public String getAcademicYear() { return academicYear; }
    public void displaySemester() { System.out.println("Semester: " + semesterNumber + " | Academic year: " + academicYear); }
}

// UNIT II: Faculty object and behavior.
class Faculty {
    private final int facultyId;
    private String facultyName;
    private String departmentName;
    public Faculty(int facultyId, String facultyName, String departmentName) {
        this.facultyId = facultyId; this.facultyName = facultyName; this.departmentName = departmentName;
    }
    public void displayFaculty() {
        System.out.println("Faculty ID: " + facultyId + " | Name: " + facultyName + " | Department: " + departmentName);
    }
}

// UNIT IV: JDBC class. try-with-resources closes Connection/PreparedStatement/ResultSet.
class DBConnection {
    private static final String URL = System.getenv().getOrDefault("DB_URL", "jdbc:mysql://localhost:3306/student_course_db");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "");
    public static Connection getConnection() throws SQLException {
        // JDBC: DriverManager creates a connection to MySQL.
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}

// UNIT IV: Generic collections. Java equivalents of the C# collections in the PS.
class StudentManager {
    // Map<Integer, Student> is the Java equivalent of Dictionary<int, Student>.
    private final Map<Integer, Student> students = new HashMap<>();
    // Map<String, Course> is the Java equivalent of Dictionary<string, Course>.
    private final Map<String, Course> courses = new HashMap<>();
    // Set<String> is the Java equivalent of HashSet<string> for completed course codes.
    private final Set<String> completedCourses = new HashSet<>();
    // Queue<Integer> is the Java equivalent of Queue<int> for waitlisted student IDs.
    private final Map<String, Queue<Integer>> waitlists = new HashMap<>();

    public Map<Integer, Student> getStudents() { return students; }
    public Map<String, Course> getCourses() { return courses; }
    public Set<String> getCompletedCourses() { return completedCourses; }
    public Map<String, Queue<Integer>> getWaitlists() { return waitlists; }

    // JDBC + PreparedStatement: load students from persistent database into generic Map.
    public void loadStudents() throws SQLException {
        String sql = "SELECT student_id, student_name, email, semester, department_id, student_type FROM students";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            students.clear();
            while (rs.next()) {
                int id = rs.getInt("student_id");
                String type = rs.getString("student_type");
                Student s = "POSTGRADUATE".equalsIgnoreCase(type)
                        ? new PostgraduateStudent(id, rs.getString("student_name"), rs.getString("email"), rs.getInt("semester"), rs.getInt("department_id"))
                        : new UndergraduateStudent(id, rs.getString("student_name"), rs.getString("email"), rs.getInt("semester"), rs.getInt("department_id"));
                students.put(id, s);
            }
        }
    }

    // JDBC: add Student row. PreparedStatement prevents SQL injection.
    public void addStudent(Student s) throws SQLException {
        String sql = "INSERT INTO students(student_id,student_name,email,semester,student_type,department_id) VALUES(?,?,?,?,?,?)";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, s.getStudentId()); ps.setString(2, s.getStudentName()); ps.setString(3, s.getEmail());
            ps.setInt(4, s.getSemester()); ps.setString(5, s.getStudentType()); ps.setInt(6, s.getDepartmentId());
            ps.executeUpdate();
        }
        students.put(s.getStudentId(), s); // in-memory generic collection cache
    }

    // JDBC: load courses from persistent database.
    public void loadCourses() throws SQLException {
        String sql = "SELECT course_code,course_name,credits,capacity,prerequisite_code FROM courses";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            courses.clear();
            while (rs.next()) courses.put(rs.getString("course_code"), new Course(rs.getString("course_code"),
                    rs.getString("course_name"), rs.getInt("credits"), rs.getInt("capacity"), rs.getString("prerequisite_code")));
        }
    }

    // PART A: behavior. Message passing: Student request -> StudentManager -> Course/database.
    public void registerCourse(int studentId, String courseCode)
            throws SQLException, InvalidRegistrationException, PrerequisiteNotMetException, CourseFullException {
        Student student = students.get(studentId);
        Course course = courses.get(courseCode);
        if (student == null) throw new InvalidRegistrationException("Student ID does not exist.");
        if (course == null) throw new InvalidRegistrationException("Course code does not exist.");

        // UNIT IV: custom exception for duplicate registration; database also has UNIQUE constraint.
        String checkSql = "SELECT status FROM registrations WHERE student_id=? AND course_code=?";
        try (Connection con = DBConnection.getConnection(); PreparedStatement check = con.prepareStatement(checkSql)) {
            check.setInt(1, studentId); check.setString(2, courseCode);
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) throw new InvalidRegistrationException("Duplicate course enrollment is not allowed.");
            }
        }

        // UNIT IV: prerequisite enforcement using HashSet<String> (completed course codes).
        String prerequisite = course.getPrerequisiteCode();
        if (prerequisite != null && !hasPassedCourse(studentId, prerequisite))
            throw new PrerequisiteNotMetException("Complete and pass prerequisite " + prerequisite + " first.");

        int enrolled = countConfirmed(courseCode);
        if (enrolled >= course.getCapacity()) {
            // Structured exception + Queue<Integer> waitlist handling.
            waitlists.computeIfAbsent(courseCode, k -> new LinkedList<>()).offer(studentId);
            saveRegistration(studentId, courseCode, "WAITLISTED");
            throw new CourseFullException("Course is full. Student has been added to the waitlist.");
        }
        saveRegistration(studentId, courseCode, "CONFIRMED");
        System.out.println("Registration confirmed successfully.");
    }

    private boolean hasPassedCourse(int studentId, String courseCode) throws SQLException {
        String sql = "SELECT marks FROM grades WHERE student_id=? AND course_code=?";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId); ps.setString(2, courseCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                // Add completed code to Set only if marks >= 40.
                if (rs.getDouble("marks") >= 40) { completedCourses.add(courseCode); return true; }
                return false;
            }
        }
    }

    private int countConfirmed(String courseCode) throws SQLException {
        String sql = "SELECT COUNT(*) FROM registrations WHERE course_code=? AND status='CONFIRMED'";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, courseCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
                return 0;
            }
        }
    }

    private void saveRegistration(int studentId, String courseCode, String status) throws SQLException {
        String sql = "INSERT INTO registrations(student_id,course_code,status) VALUES(?,?,?)";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId); ps.setString(2, courseCode); ps.setString(3, status); ps.executeUpdate();
        }
    }

    // UNIT IV: validate grade range; custom exception generation with throw.
    public void recordGrade(int studentId, String courseCode, double marks) throws SQLException, InvalidGradeException {
        if (marks < 0 || marks > 100) throw new InvalidGradeException("Marks must be between 0 and 100.");
        if (!students.containsKey(studentId)) throw new InvalidGradeException("Student does not exist.");
        if (!courses.containsKey(courseCode)) throw new InvalidGradeException("Course does not exist.");
        String sql = "INSERT INTO grades(student_id,course_code,marks) VALUES(?,?,?) ON DUPLICATE KEY UPDATE marks=VALUES(marks)";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId); ps.setString(2, courseCode); ps.setDouble(3, marks); ps.executeUpdate();
        }
        if (marks >= 40) completedCourses.add(courseCode);
        System.out.println("Grade saved. GPA (simple average on 10-point scale): " + calculateGpa(studentId));
    }

    // UNIT IV: database query + GPA calculation.
    public double calculateGpa(int studentId) throws SQLException {
        String sql = "SELECT AVG(marks) FROM grades WHERE student_id=?";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double average = rs.getDouble(1);
                    return rs.wasNull() ? 0.0 : Math.round((average / 10.0) * 100.0) / 100.0;
                }
                return 0.0;
            }
        }
    }

    public void displayStudents() {
        if (students.isEmpty()) System.out.println("No students loaded.");
        for (Student s : students.values()) s.displayStudent();
    }
    public void displayCourses() { for (Course c : courses.values()) c.displayCourseDetails(); }

    // JDBC report: registrations with student and course data (JOIN demonstrates relational design).
    public void displayRegistrations() throws SQLException {
        String sql = "SELECT r.student_id,s.student_name,r.course_code,c.course_name,r.status FROM registrations r JOIN students s ON s.student_id=r.student_id JOIN courses c ON c.course_code=r.course_code ORDER BY r.registration_id";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) System.out.println("Student: " + rs.getInt("student_id") + " - " + rs.getString("student_name")
                    + " | Course: " + rs.getString("course_code") + " - " + rs.getString("course_name") + " | " + rs.getString("status"));
        }
    }
}

// MAIN class follows the notes' simple menu-driven/System.out.println format.
public class Main {
    public static void main(String[] args) {
        StudentManager manager = new StudentManager();
        try {
            manager.loadStudents();
            manager.loadCourses();
            System.out.println("Database connected. Student Course Registration System");
        } catch (SQLException e) {
            System.out.println("Database connection/loading error: " + e.getMessage());
            System.out.println("Check MySQL, schema.sql and DB_URL/DB_USER/DB_PASSWORD.");
            return;
        }

        try (Scanner sc = new Scanner(System.in)) {
            int choice = -1;
            while (choice != 0) {
                System.out.println("\n===== STUDENT COURSE REGISTRATION SYSTEM =====");
                System.out.println("1. Display Students");
                System.out.println("2. Display Courses");
                System.out.println("3. Add Student");
                System.out.println("4. Register Student for Course");
                System.out.println("5. Record Grade / Calculate GPA");
                System.out.println("6. Display Registrations");
                System.out.println("7. Demonstrate Runtime Polymorphism");
                System.out.println("0. Exit");
                System.out.print("Enter choice: ");
                try {
                    choice = Integer.parseInt(sc.nextLine().trim());
                    switch (choice) {
                        case 1 -> manager.displayStudents();
                        case 2 -> manager.displayCourses();
                        case 3 -> {
                            System.out.print("Student ID: "); int id = Integer.parseInt(sc.nextLine().trim());
                            System.out.print("Student name: "); String name = sc.nextLine().trim();
                            System.out.print("Email: "); String email = sc.nextLine().trim();
                            System.out.print("Semester: "); int sem = Integer.parseInt(sc.nextLine().trim());
                            System.out.print("Department ID (1=IT, 2=Computer): "); int dept = Integer.parseInt(sc.nextLine().trim());
                            System.out.print("Type (U=Undergraduate, P=Postgraduate): "); String type = sc.nextLine().trim();
                            // UNIT III: base reference + child object demonstrates dynamic binding.
                            Student student = type.equalsIgnoreCase("P")
                                    ? new PostgraduateStudent(id, name, email, sem, dept)
                                    : new UndergraduateStudent(id, name, email, sem, dept);
                            manager.addStudent(student);
                            System.out.println("Student saved using JDBC.");
                        }
                        case 4 -> {
                            System.out.print("Student ID: "); int id = Integer.parseInt(sc.nextLine().trim());
                            System.out.print("Course code: "); String code = sc.nextLine().trim().toUpperCase();
                            try {
                                // UNIT IV: try-catch-finally structured exception handling.
                                manager.registerCourse(id, code);
                            } catch (PrerequisiteNotMetException e) {
                                System.out.println("Prerequisite error: " + e.getMessage());
                            } catch (CourseFullException e) {
                                System.out.println("Capacity/waitlist notice: " + e.getMessage());
                            } catch (InvalidRegistrationException e) {
                                System.out.println("Registration error: " + e.getMessage());
                            } finally {
                                System.out.println("Registration attempt completed."); // finally always executes
                            }
                        }
                        case 5 -> {
                            System.out.print("Student ID: "); int id = Integer.parseInt(sc.nextLine().trim());
                            System.out.print("Course code: "); String code = sc.nextLine().trim().toUpperCase();
                            System.out.print("Marks (0-100): "); double marks = Double.parseDouble(sc.nextLine().trim());
                            try {
                                manager.recordGrade(id, code, marks);
                            } catch (InvalidGradeException e) {
                                System.out.println("Grade error: " + e.getMessage());
                            } finally {
                                System.out.println("Grade operation completed.");
                            }
                        }
                        case 6 -> manager.displayRegistrations();
                        case 7 -> {
                            for (Student s : manager.getStudents().values()) {
                                // Runtime polymorphism / dynamic binding: overridden method chosen by actual object type.
                                System.out.println(s.getStudentName() + ": " + s.getRegistrationGuidance());
                            }
                        }
                        case 0 -> System.out.println("Exiting Student Management System.");
                        default -> System.out.println("Invalid menu choice.");
                    }
                } catch (NumberFormatException e) {
                    // UNIT IV: catch unchecked input conversion exception.
                    System.out.println("Invalid numeric input. Please enter a valid number.");
                } catch (SQLException e) {
                    // UNIT IV: catch database exception.
                    System.out.println("Database operation failed: " + e.getMessage());
                } finally {
                    // UNIT IV: finally block executes after each menu operation.
                    // Input scanner remains open until the user exits.
                }
            }
        }
    }
}
