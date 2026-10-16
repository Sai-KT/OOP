# EduBridge Student Management System — Cloud Deployment Guide

This guide provides end-to-end instructions for deploying the **EduBridge Student Management System** to a free cloud hosting tier using:
1. **Aiven Free MySQL** — Persistent cloud database with automatic backups and TLS encryption.
2. **Render Free Web Service** — Java 17 containerized web service hosting the backend and static frontend.

---

## 1. Architecture Overview

```
+-------------------------------------------------------------+
|                      CLIENT BROWSER                         |
|   https://edubridge.onrender.com (HTTPS terminated)         |
+------------------------------+------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|               RENDER FREE WEB SERVICE (Container)           |
|  - Eclipse Temurin Java 17 Alpine Container                 |
|  - Built-in Java HttpServer on $PORT                        |
|  - Static Frontend: /frontend (HTML, CSS, JS, Assets)       |
|  - Session Cookies: EBSESSION (HttpOnly, SameSite=Lax)      |
|  - Reverse-Proxy SSL detection via X-Forwarded-Proto        |
+------------------------------+------------------------------+
                               |
                               | JDBC over TLS (sslMode=REQUIRED)
                               v
+-------------------------------------------------------------+
|                    AIVEN CLOUD MYSQL (Free Tier)            |
|  - MySQL 8.0+ / MySQL 9.0+                                  |
|  - Schema: defaultdb / student_course_db                    |
|  - Tables: departments, students, courses, registrations,   |
|            grades, department_grades, attendance, users     |
+-------------------------------------------------------------+
```

---

## 2. Phase 1: Set Up Aiven Cloud MySQL

### Step 1: Create an Aiven Free MySQL Service
1. Sign up or log in at [https://console.aiven.io](https://console.aiven.io).
2. Click **Create Service**.
3. Select **MySQL**.
4. Choose the **Free Plan** (available in supported regions like AWS Frankfurt or Google Cloud).
5. Give your service a name, e.g., `edubridge-mysql`.
6. Click **Create Service**. Wait 2–3 minutes for the state to transition to **Running**.

### Step 2: Retrieve Connection Details
In your Aiven service **Overview** tab, note the following credentials:
- **Host:** e.g., `mysql-xxxx.aivencloud.com`
- **Port:** e.g., `12345`
- **User:** `avnadmin`
- **Password:** *(click to reveal or copy)*
- **Database:** `defaultdb` (or create a database named `student_course_db`)
- **Service URI:** e.g., `mysql://avnadmin:PASSWORD@mysql-xxxx.aivencloud.com:12345/defaultdb?ssl-mode=REQUIRED`

### Step 3: Construct the JDBC Connection URL
MySQL Connector/J in EduBridge requires a JDBC URL formatted as follows:
```text
jdbc:mysql://<HOST>:<PORT>/<DATABASE>?sslMode=REQUIRED
```
*Example:*
```text
jdbc:mysql://mysql-xxxx.aivencloud.com:12345/defaultdb?sslMode=REQUIRED
```

### Step 4: Initialize the Database Schema
You can execute the initialization script either via MySQL CLI, MySQL Workbench, or the Aiven Web Query Editor:

#### Option A: Using the MySQL Command-Line Client
```bash
mysql -h <HOST> -P <PORT> -u avnadmin -p defaultdb < sql/schema.sql
```

#### Option B: Using MySQL Workbench
1. Create a new MySQL Connection in Workbench:
   - **Hostname:** `<HOST>`
   - **Port:** `<PORT>`
   - **Username:** `avnadmin`
   - **SSL:** Choose **Require** or **Require and Verify CA**.
2. Connect and open `sql/schema.sql`.
3. Note: If your Aiven database is named `defaultdb`, remove or comment out `USE student_course_db;` at the top of `sql/schema.sql` so that tables are created in `defaultdb`.
4. Execute the SQL script. Verify that all 8 tables are created:
   - `departments`
   - `students`
   - `courses`
   - `registrations`
   - `grades`
   - `department_grades`
   - `attendance`
   - `users`

---

## 3. Phase 2: Deploy to Render Free Web Service

EduBridge includes a pre-configured, production-hardened multi-stage `Dockerfile`. Render will automatically build the container and deploy the web service.

### Step 1: Connect Your Repository to Render
1. Push your repository changes to GitHub.
2. Log in to [https://dashboard.render.com](https://dashboard.render.com).
3. Click **New +** → **Web Service**.
4. Select **Build and deploy from a Git repository**.
5. Connect your GitHub account and select your `OOP` repository.

### Step 2: Configure Service Settings
- **Name:** `edubridge-sms` (or your preferred name)
- **Region:** Choose the region closest to your Aiven MySQL database (e.g., Frankfurt or Oregon).
- **Branch:** `main`
- **Runtime:** **Docker**
- **Instance Type:** **Free**

### Step 3: Configure Environment Variables
Under the **Environment Variables** section in Render, click **Add Environment Variable** for each of the following:

| Key | Value | Description |
| :--- | :--- | :--- |
| `DB_URL` | `jdbc:mysql://<HOST>:<PORT>/defaultdb?sslMode=REQUIRED` | Aiven MySQL JDBC connection string |
| `DB_USER` | `avnadmin` | Database username |
| `DB_PASSWORD` | `<your-aiven-password>` | Database password |
| `EDUBRIDGE_ADMIN_NAME` | `EduBridge Administrator` | Full name of the initial administrator |
| `EDUBRIDGE_ADMIN_USERNAME` | `Admin` | Initial administrator login username |
| `EDUBRIDGE_ADMIN_EMAIL` | `admin@edubridge.edu` | Initial administrator email |
| `EDUBRIDGE_ADMIN_PASSWORD` | `Pass@1234` | Initial administrator password (min 8 chars) |
| `COOKIE_SECURE` | `true` | Enforces HTTPS-only cookies |

> [!NOTE]
> Render sets the `PORT` variable automatically (typically port `10000`). The EduBridge `WebServer` dynamically reads `System.getenv("PORT")` and binds to it automatically.

### Step 4: Trigger the Deployment
1. Click **Create Web Service**.
2. Render will pull the repository, run the multi-stage Docker build:
   - Stage 1 compiles Java classes and copies runtime dependencies (`mysql-connector-j-9.4.0.jar`).
   - Stage 2 packages the minimal Alpine JRE container with static assets.
3. Once the build completes, the logs will show:
   ```text
   EduBridge: Initial administrator account created successfully: Admin
   EduBridge dashboard running at http://localhost:10000
   Serving frontend from: /app/frontend
   ```
4. Render will mark the service **Live** and provide a public HTTPS URL:
   `https://edubridge-sms.onrender.com`

---

## 4. Phase 3: Post-Deployment Verification

### 1. Test Admin Login
1. Open `https://<your-app>.onrender.com/admin-login.html`.
2. Enter the bootstrapped admin credentials:
   - **Username:** `Admin`
   - **Password:** `Pass@1234`
3. Click **Sign in to Admin Console**.
4. Confirm successful redirection to `index.html` and verify the admin sidebar, student list, and department metrics.

### 2. Test Student Public Registration & Account Linking
1. Open an incognito browser window and navigate to `https://<your-app>.onrender.com/student-login.html`.
2. Switch to the **Create Account** tab.
3. Sign up with a new student account (e.g., `student1`, `student1@example.com`, `Password123!`).
4. Sign in. Confirm that the student portal displays the **Account Pending Verification** screen (since public signups cannot link themselves to a student record).
5. Switch back to your Admin Console in `index.html`.
6. Go to **Account Management**, find `student1`, and link them to an existing student record (e.g., student ID `101`).
7. Refresh the student portal in the incognito window. Confirm that the student's personal profile, enrolled courses, attendance, grades, and GPA are now displayed.

### 3. Test Security Boundary
- Try accessing `https://<your-app>.onrender.com/index.html` from the student session or an unauthenticated browser: verify that access is blocked with HTTP 403 or redirected to `/admin-login.html`.
- Inspect cookie headers in your browser's Developer Tools: verify that `EBSESSION` has `HttpOnly`, `SameSite=Lax`, and `Secure` attributes set.

---

## 5. Maintenance & Troubleshooting

### Database Schema Updates
If schema updates or migrations are required in the future:
1. Migrations are located in `sql/migrations/`.
2. Check `sql/migrations/README.md` for individual migration verification and rollback instructions.
3. Always back up your Aiven database before executing manual schema modifications.

### Free Tier Sleep/Spin-Down Behavior
On Render's Free tier:
- If inactive for 15 minutes, the web service will spin down.
- Incoming requests will automatically spin up the service (cold start takes ~30–45 seconds).
- All persistent data remains permanently intact in Aiven MySQL.
