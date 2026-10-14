# EduBridge Dashboard (Java + JDBC + MySQL)

Main.java, sql/schema.sql, pom.xml, README.md and DESIGN.md are NOT changed.

## New files
| File | What it does |
|---|---|
| src/main/java/com/studentmanagement/WebServer.java | Web server (JDK HttpServer) + JSON API + login protection |
| src/main/java/com/studentmanagement/AuthManager.java | Sign up / login (users table, salted SHA-256 passwords, sessions) |
| src/main/java/com/studentmanagement/AttendanceManager.java | Attendance (attendance table) |
| frontend/login.html, frontend/login.js | Login + sign-up page |
| frontend/index.html, frontend/script.js, frontend/style.css | Dashboard |
| sample_pdfs/*.pdf | Example PDFs for the "Import PDF" buttons |

The `users` and `attendance` tables are created automatically when WebServer starts.

## Run
```bash
mysql -u root -p < sql/schema.sql          # first time only
export DB_PASSWORD='your real MySQL password'
mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" com.studentmanagement.WebServer
```
Open http://localhost:8080 → login page → "Create an account" → sign in → dashboard.
(Windows: use `;` instead of `:` in the -cp value.)

## PDF import (Students, Enrollment, Attendance, Grades pages → "📄 Import PDF")
| Page | One row per line |
|---|---|
| Students | Student ID, Full name, Email, Year, Semester, Department |
| Enrollment | Student ID, Course code |
| Attendance | Student ID, Course code, Date (YYYY-MM-DD or DD-MM-YYYY), Present/Absent/Late |
| Grades | Student ID, Department ID, Marks |

The PDF must contain text (made from Word/Excel/Google Docs), not a scanned image.
pdf.js loads from cdnjs, so the browser needs internet. CSV/TXT files also work.
Departments page: add a department, view its students, or move a student into it.
