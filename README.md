# Student Course Registration and Management System — JDBC (No Spring Boot)

This is a simple Java 17 + JDBC + MySQL console version of the Student Management System. It follows the `System.out.println()` style of the supplied notes and marks relevant OOP topics with comments in `Main.java`.

## Topics covered
- Case Study 1 Part A: Student, Course, Faculty, Registration, Department, Semester; attributes, behaviors, message passing.
- Case Study 1 Part B: class and object relationships explained in `DESIGN.md` with class and interaction diagrams.
- Unit II: classes/objects, constructors, encapsulation, private fields/getters/setters, static member, association, JDBC data access.
- Unit III / Group 06: `Student` superclass, `UndergraduateStudent` and `PostgraduateStudent`, overriding, runtime polymorphism, dynamic binding.
- Unit IV / Group 06: `try-catch-finally`, custom exceptions, duplicate registration validation, prerequisites, course capacity and waitlist, invalid grade range, GPA.
- Generic collections: Java equivalents `Map<Integer, Student>`, `Map<String, Course>`, `Set<String>`, and `Queue<Integer>`.
- JDBC: `DriverManager`, `Connection`, `PreparedStatement`, `ResultSet`, SQL joins, insert/update/select, try-with-resources.

> The supplied Group 06 statement uses C#/.NET names (`Dictionary<int, Student>`, etc.). This Java project uses their Java equivalents because the provided OOP notes use Java.

## Setup
1. Install JDK 17+ and MySQL.
2. In MySQL Workbench, open and run `sql/schema.sql`.
3. Set database credentials. Defaults are URL `jdbc:mysql://localhost:3306/student_course_db`, user `root`, and blank password. Prefer environment variables:
   - `DB_URL=jdbc:mysql://localhost:3306/student_course_db`
   - `DB_USER=your_mysql_username`
   - `DB_PASSWORD=your_mysql_password`
4. Run from this folder:
   ```bash
   mvn compile exec:java
   ```
   Or open as a Maven project in IntelliJ/Eclipse and run `com.studentmanagement.Main`.
5. Sample courses are inserted by `schema.sql`. Add a student first. To register for `IT201`, first record passing marks (40 or above) for `IT101` for that student.

## Important behavior
- Course capacity is set to 2 for the sample courses so the waitlist path can be demonstrated quickly.
- Marks are validated in the 0–100 range. GPA shown is a simple average of marks divided by 10; it is an educational demonstration, not a weighted-credit GPA policy.
- JDBC stores persistent data in MySQL. Java collections are in-memory lookup/cache structures and are repopulated from the database at startup.
- Password/authentication and browser UI are not included in this console JDBC version. It is a direct-JDBC academic version of the core case-study system, not a drop-in replacement for the original Spring Boot web API.
