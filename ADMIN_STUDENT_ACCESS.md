# EduBridge — Admin & Student RBAC Guide (`ADMIN_STUDENT_ACCESS.md`)

This guide explains the Role-Based Access Control (RBAC) architecture, authentication lifecycle, account-to-student linking model, and demonstration instructions for **EduBridge Student Management System**.

---

## 1. How Role-Based Authorization Works

EduBridge implements a multi-tier, defense-in-depth authorization model built on vanilla Java (`com.sun.net.httpserver.HttpServer`), JDBC, and MySQL.

### Architectural Flow

```
[ Browser / API Client ]
         │
         │  HTTP Request (Cookie: EBSESSION=<token>)
         ▼
[ WebServer Route Handlers ]
         │
         ├── 1. Session Extraction: Checks HttpOnly SameSite=Lax cookie.
         │      If missing/invalid ──► Returns 401 Unauthorized
         │
         ├── 2. Identity Verification: AuthManager.getUser(token)
         │      Validates cached session & syncs active MySQL row.
         │
         ├── 3. Role Authorization:
         │      ├── adminOnly(handler): Verifies user.getRole() == Role.ADMIN
         │      │   └── Non-Admin ──► Returns 403 Forbidden
         │      │
         │      └── studentOnly(handler): Verifies user.getRole() == Role.STUDENT
         │          ├── Non-Student ──► Returns 403 Forbidden
         │          └── student_id is NULL ──► Returns 403 (Unlinked Account)
         │
         ▼
[ Service / JDBC Execution ] (StudentPortalService, Main Manager Classes)
```

### HTTP Status Code Contracts

- **`401 Unauthorized`**: Request lacks a valid, active session token.
- **`403 Forbidden`**: User is authenticated, but their authoritative role lacks permissions, or an unlinked student attempts to read academic data.
- **`405 Method Not Allowed`**: Request method is unsupported (e.g. `POST` sent to a read-only endpoint).
- **`409 Conflict`**: Attempt to link an already-linked student record or assign duplicate entity.

---

## 2. How Users and Students are Linked

### Separation of Concerns

EduBridge purposefully decouples **Login Credentials (`users`)** from **Institutional Academic Records (`students`)**:
1. **Academic Records (`students`)**: Pre-populated by the registrar containing grades, enrollments, departmental records, and semester progress.
2. **User Accounts (`users`)**: Created when an individual signs up or when an administrator is bootstrapped. Contains username, password salt/hash, and role.

### The Link Model in MySQL

In `sql/migrations/001_add_user_roles.sql`:
```sql
ALTER TABLE users
  ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'STUDENT',
  ADD COLUMN student_id INT NULL,
  ADD CONSTRAINT chk_user_role CHECK (role IN ('ADMIN', 'STUDENT')),
  ADD CONSTRAINT uq_users_student_id UNIQUE (student_id),
  ADD CONSTRAINT fk_users_student FOREIGN KEY (student_id)
      REFERENCES students(student_id) ON DELETE SET NULL ON UPDATE CASCADE;
```

- `role`: Enforces either `ADMIN` or `STUDENT`.
- `student_id`: Nullable integer foreign key. Administrators have `student_id = NULL`.
- `uq_users_student_id`: Enforces a **1-to-1** mapping. One student record cannot be linked to multiple user accounts.

### Admin-Controlled Account Linking Flow

1. A student registers an account on `/login.html#signup`. The account is initially unlinked (`student_id = NULL`).
2. An Administrator logs into `/index.html` and navigates to the **Student Accounts** section (`#accountsPage`).
3. The Admin reviews the account's legal name and email address.
4. The Admin clicks **Link Student Record**, selects the verified student from the dropdown, checks the verification confirmation checkbox, and clicks **Confirm Link**.
5. The backend validates that:
   - Target account exists and has role `STUDENT`.
   - Selected `student_id` exists in `students`.
   - Neither account nor student is already mapped elsewhere.
6. The session is dynamically refreshed via `AuthManager.refreshUserStudentLink(...)` and the student immediately gains access to their personal dashboard.

---

## 3. How the Initial Administrator is Created

EduBridge prohibits public self-registration of administrator accounts. Administrators can only be created via secure server-side provisioning:

### Option A: Environment Variable Bootstrap (`AuthManager.bootstrapAdmin()`)
Configure environment variables before launching the server:
```bash
export EDUBRIDGE_ADMIN_USERNAME="sysadmin"
export EDUBRIDGE_ADMIN_EMAIL="admin@edubridge.edu"
export EDUBRIDGE_ADMIN_PASSWORD="AdminSecurePassword123!"
export EDUBRIDGE_ADMIN_NAME="Head Administrator"
```
When `WebServer` starts, `bootstrapAdmin()`:
- Verifies that no `ADMIN` account currently exists in `users`.
- Verifies that the username and email are not taken.
- Generates a 16-byte cryptographic salt and computes PBKDF2/SHA-256 hash.
- Inserts the `ADMIN` record with `student_id = NULL`.

### Option B: CLI Setup via `Main.java`
Launch the console application:
```bash
java -cp "target/classes:$(cat cp.txt)" com.studentmanagement.Main
```
Choose menu option `11. Setup Administrator Account` to configure the credentials interactively.

---

## 4. How to Log In as ADMIN and STUDENT

1. Open your browser and navigate to `http://localhost:8080/login.html`.
2. **Administrator Login**:
   - **Username**: `sysadmin`
   - **Password**: `AdminPass123!`
   - *Result*: Redirects automatically to the **Admin Dashboard** (`/index.html`).
3. **Student Login (Linked)**:
   - **Username**: `raj101`
   - **Password**: `StudentPass123!`
   - *Result*: Redirects automatically to the **Student Portal** (`/student.html`).
4. **Student Login (Unlinked)**:
   - Create a new account on `/login.html#signup`.
   - Log in with your new credentials.
   - *Result*: Opens `student.html` displaying the friendly pending notice:
     > *"Your account has not been linked to a student record yet. Please contact your administrator."*

---

## 5. Demonstrating the Separated Portals and Login Pages

EduBridge provides completely isolated portals and dedicated sign-in pages to guarantee role separation:

| Feature Area | Admin Portal (`/index.html`) | Student Portal (`/student.html`) |
|--------------|--------------------------------|----------------------------------|
| **Dedicated Login Page** | `/admin-login.html` | `/student-login.html` |
| **Target Role** | `ADMIN` only (403 for Students) | `STUDENT` only (302/403 for Admins) |
| **Login Isolation** | Rejects students with 403 Forbidden | Rejects administrators with 403 Forbidden |
| **Direct Asset Access** | `/script.js` blocked with 403 for Students | `/student.js` available |
| **Topbar Badge** | Purple `ADMIN` badge | Blue `STUDENT` badge |
| **Overview** | Institution-wide metrics (all students, seats used, global attendance) | Personal metrics (enrolled courses, personal attendance %, cumulative GPA) |
| **Students** | Add students, search all students, import PDFs, export CSV | Read-only personal academic profile |
| **Departments** | Create departments, assign students | Read-only enrolled department |
| **Courses** | Manage capacities, assign courses | View registered courses & enrollment status |
| **Attendance** | Mark classroom attendance (Present, Late, Absent) | View verified personal attendance logs & breakdown |
| **Grades** | Enter and modify departmental marks | View published departmental grades & GPA |
| **Account Access** | Link and unlink student accounts | N/A (hidden) |
| **Reports** | Institution-wide enrollment & performance reports | N/A (hidden) |

---

## 6. What Java Classes Were Modified

1. **`Role.java` (New Enum)**:
   - Implements `ADMIN` and `STUDENT` roles.
2. **`AuthManager.java` (Updated)**:
   - Updated `UserAccount` with private `Role role` and `Integer studentId`.
   - Added `bootstrapAdmin()` for secure credential provisioning.
   - Restricted public `signup(...)` to `Role.STUDENT` and `student_id = NULL`.
   - Added `refreshUserStudentLink(userId, studentId)` for zero-latency session synchronization.
   - Refactored `getUser(token)` to cross-verify live database rows.
3. **`WebServer.java` (Updated)**:
   - Added `adminOnly(handler)` and `studentOnly(handler)` centralized authorization decorators.
   - Integrated `StudentPortalService` read-only endpoints under `/api/student/*`.
   - Added Admin account listing and linking endpoints under `/api/admin/accounts*`.
   - Added static route protection for `/index.html` and `/student.html`.
4. **`StudentPortalService.java` (New Service)**:
   - Encapsulates read-only JDBC queries strictly scoped to the authenticated student ID.
5. **`Main.java` (Updated)**:
   - Added CLI administration menu and connection configuration via environment variables.

---

## 7. What Database Columns Were Added

Migration script: `sql/migrations/001_add_user_roles.sql`

| Table | Added Column | Type & Constraints | Purpose |
|-------|--------------|-------------------|---------|
| `users` | `role` | `VARCHAR(20) NOT NULL DEFAULT 'STUDENT'`<br>`CHECK (role IN ('ADMIN', 'STUDENT'))` | Role differentiation |
| `users` | `student_id` | `INT NULL`<br>`UNIQUE (uq_users_student_id)`<br>`FOREIGN KEY REFERENCES students(student_id)` | 1-to-1 link to student record |

---

## 8. OOP Syllabus Concepts Demonstrated

This project serves as a showcase of Object-Oriented Programming (OOP) in Java:

1. **Encapsulation (Unit II)**:
   - `UserAccount`: All state attributes (`userId`, `fullName`, `username`, `email`, `role`, `studentId`) are declared `private` with public getter methods and no mutating setters.
2. **Polymorphism & Functional Interfaces (Unit III)**:
   - Custom `StudentHandler` and `HttpHandler` functional interfaces used with lambda expressions in `adminOnly` and `studentOnly` higher-order functions.
3. **Inheritance & Hierarchy (Unit III)**:
   - Custom exception hierarchy: `InvalidLoginException`, `InvalidSignupException`, `DuplicateUserException` extending standard Java `Exception`.
4. **Type-Safe Enumerations (Unit III)**:
   - `Role` enum prevents stringly-typed authorization errors.
5. **Generic Collections (Unit IV)**:
   - `ConcurrentHashMap<String, UserAccount>` for thread-safe session tracking.
   - `Map<Integer, Student>`, `Queue<Integer>` for waitlists, and `Set<String>` for prerequisites.
6. **Robust Resource Management (Unit IV)**:
   - Extensive usage of Java `try-with-resources` ensuring all `Connection`, `PreparedStatement`, and `ResultSet` handles are automatically closed.

---

## 9. Exact Commands for Running the Application

### 1. Compile and Run Automated Tests
```bash
mvn clean test
```

### 2. Start the Server
```bash
java -cp "target/classes:$(cat cp.txt)" com.studentmanagement.WebServer
```

### 3. Open Dashboards & Dedicated Portals
- **Administrator Login Page**: `http://localhost:8080/admin-login.html` (strictly for ADMIN accounts)
- **Administrator Dashboard**: `http://localhost:8080/index.html` (403 for students, redirects unauthenticated to admin-login)
- **Student Login & Registration**: `http://localhost:8080/student-login.html` (strictly for STUDENT accounts)
- **Student Academic Portal**: `http://localhost:8080/student.html` (redirects unauthenticated to student-login)
- **Root URL**: `http://localhost:8080/` (forwards unauthenticated users to `/student-login.html` and authenticated users to their respective dashboard)
