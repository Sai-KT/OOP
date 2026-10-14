# EduBridge Database Migrations

## Migration 001: Add User Roles (`001_add_user_roles.sql`)

### 1. Overview & Mechanics
This migration introduces **Role-Based Access Control (RBAC)** to the EduBridge database (`student_course_db`):
- Introduces two roles: `ADMIN` and `STUDENT`.
- Adds a nullable `student_id` column to the `users` table linked to `students(student_id)`.
- Implements the principle of least privilege: all existing and newly inserted accounts strictly default to `STUDENT`.
- Enforces strict data integrity via MySQL constraints:
  - `chk_user_role`: Restricts `role` values strictly to `'ADMIN'` or `'STUDENT'`.
  - `uq_users_student_id`: Ensures a student record cannot be linked to more than one user account. (MySQL allows multiple `NULL` entries for this unique key, allowing admins and unlinked accounts to coexist safely).
  - `fk_users_student`: Enforces foreign key referential integrity against `students(student_id)`.
- Preserves all existing user records, password hashes, salts, and timestamps without deleting or recreating the table.

---

### 2. How to Back Up the Database Before Migration
Always take a snapshot of the database before applying schema modifications.

#### Option A: Terminal (`mysqldump`)
Run from your terminal:
```bash
mysqldump -u root -p student_course_db > backup_student_course_db_$(date +%Y%m%d_%H%M%S).sql
```
*(If your root user has no password, omit `-p`)*

#### Option B: MySQL Workbench
1. Open MySQL Workbench and connect to your local MySQL instance.
2. In the top menu, go to **Server** → **Data Export**.
3. Under **Tables to Export**, select `student_course_db`.
4. Select **Dump Structure and Data**.
5. Choose **Export to Self-Contained File**.
6. Click **Start Export**.

---

### 3. How to Run the Migration Once in MySQL Workbench
1. Launch **MySQL Workbench** and connect to your database instance.
2. Go to **File** → **Open SQL Script...** (or press `Ctrl+Shift+O` / `Cmd+Shift+O`).
3. Select `sql/migrations/001_add_user_roles.sql`.
4. Review the script in the SQL Editor tab.
5. Click the **Execute (Execute all or selection / ⚡ Lightning Bolt)** icon.
6. Check the **Action Output** panel at the bottom: you should see green checkmarks for `ALTER TABLE users` and `UPDATE users`.

---

### 4. How to Verify Columns and Constraints
Run the following SQL queries in MySQL Workbench or terminal:

#### A. Inspect Columns
```sql
USE student_course_db;

DESCRIBE users;
```
Expected output includes:
- `role`: `varchar(20)`, `Null: NO`, `Default: STUDENT`
- `student_id`: `int`, `Null: YES`, `Key: UNI`

#### B. Inspect Constraints & Keys
```sql
SELECT 
    CONSTRAINT_NAME, 
    CONSTRAINT_TYPE 
FROM information_schema.TABLE_CONSTRAINTS 
WHERE TABLE_SCHEMA = 'student_course_db' 
  AND TABLE_NAME = 'users';
```
Expected output includes:
- `chk_user_role` (CHECK)
- `uq_users_student_id` (UNIQUE)
- `fk_users_student` (FOREIGN KEY)

#### C. Inspect Existing Users' Assigned Roles
```sql
SELECT user_id, full_name, username, email, role, student_id FROM users;
```
All pre-existing users should display `role = 'STUDENT'` and `student_id = NULL`.

---

### 5. Explicit Administrator Promotion (Post-Verification)
In accordance with security requirements, no user is automatically promoted to `ADMIN`. To promote a designated, verified user to `ADMIN`, execute an explicit query:

```sql
-- Replace 'admin' with the verified administrator username:
UPDATE users 
SET role = 'ADMIN', student_id = NULL 
WHERE username = 'admin';
```

---

### 6. Explicit Student Linking (Post-Verification)
To link an existing student user account to their student record in `students`:

```sql
-- Example: Link student username 'mrugmay' to student_id 101:
UPDATE users 
SET role = 'STUDENT', student_id = 101 
WHERE username = 'mrugmay';
```

---

### 7. How to Roll Back / Reverse the Migration (If Necessary)
If you need to revert the database schema back to its pre-migration state:

```sql
USE student_course_db;

-- 1. Drop foreign key constraint
ALTER TABLE users DROP FOREIGN KEY fk_users_student;

-- 2. Drop unique constraint
ALTER TABLE users DROP INDEX uq_users_student_id;

-- 3. Drop check constraint (MySQL 8.0.16+)
ALTER TABLE users DROP CHECK chk_user_role;

-- 4. Drop added columns
ALTER TABLE users DROP COLUMN student_id, DROP COLUMN role;
```

---

### 8. Updated Table Definition for Future Fresh Installations
For new installations, the updated `users` table definition should be placed in `sql/schema.sql` (or `AuthManager.createTable()`):

```sql
CREATE TABLE IF NOT EXISTS users (
    user_id INT AUTO_INCREMENT PRIMARY KEY,
    full_name VARCHAR(100) NOT NULL,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(150) NOT NULL UNIQUE,
    password_hash CHAR(64) NOT NULL,
    salt CHAR(32) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'STUDENT',
    student_id INT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_user_role CHECK (role IN ('ADMIN', 'STUDENT')),
    CONSTRAINT uq_users_student_id UNIQUE (student_id),
    CONSTRAINT fk_users_student FOREIGN KEY (student_id) 
        REFERENCES students(student_id) 
        ON DELETE SET NULL 
        ON UPDATE CASCADE
);
```
