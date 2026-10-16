CREATE DATABASE IF NOT EXISTS student_course_db;
USE student_course_db;

CREATE TABLE IF NOT EXISTS departments (
  department_id INT PRIMARY KEY,
  department_name VARCHAR(100) NOT NULL
);
CREATE TABLE IF NOT EXISTS students (
  student_id INT PRIMARY KEY,
  student_name VARCHAR(100) NOT NULL,
  email VARCHAR(150) NOT NULL UNIQUE,
  semester INT NOT NULL,
  student_type VARCHAR(30) NOT NULL,
  department_id INT,
  FOREIGN KEY (department_id) REFERENCES departments(department_id)
);
CREATE TABLE IF NOT EXISTS courses (
  course_code VARCHAR(20) PRIMARY KEY,
  course_name VARCHAR(120) NOT NULL,
  credits INT NOT NULL,
  capacity INT NOT NULL,
  prerequisite_code VARCHAR(20) NULL,
  faculty_id INT NULL,
  FOREIGN KEY (prerequisite_code) REFERENCES courses(course_code)
);
CREATE TABLE IF NOT EXISTS registrations (
  registration_id INT AUTO_INCREMENT PRIMARY KEY,
  student_id INT NOT NULL,
  course_code VARCHAR(20) NOT NULL,
  registration_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  status VARCHAR(20) NOT NULL,
  UNIQUE KEY uq_student_course (student_id, course_code),
  FOREIGN KEY (student_id) REFERENCES students(student_id),
  FOREIGN KEY (course_code) REFERENCES courses(course_code)
);
CREATE TABLE IF NOT EXISTS grades (
  student_id INT NOT NULL,
  course_code VARCHAR(20) NOT NULL,
  marks DECIMAL(5,2) NOT NULL,
  PRIMARY KEY (student_id, course_code),
  FOREIGN KEY (student_id) REFERENCES students(student_id),
  FOREIGN KEY (course_code) REFERENCES courses(course_code)
);

INSERT IGNORE INTO departments VALUES (1, 'Information Technology'), (2, 'Computer Engineering');
INSERT IGNORE INTO courses(course_code, course_name, credits, capacity, prerequisite_code) VALUES
('IT101','Programming Fundamentals',4,2,NULL),
('IT201','Object Oriented Programming',4,2,'IT101'),
('IT301','Database Management Systems',3,2,'IT201');


-- Department-based grades used by the EduBridge Grades & GPA page.
CREATE TABLE IF NOT EXISTS department_grades (
    student_id INT NOT NULL,
    department_id INT NOT NULL,
    marks DECIMAL(5,2) NOT NULL,
    PRIMARY KEY (student_id, department_id),
    CONSTRAINT fk_department_grade_student FOREIGN KEY (student_id) REFERENCES students(student_id),
    CONSTRAINT fk_department_grade_department FOREIGN KEY (department_id) REFERENCES departments(department_id),
    CONSTRAINT chk_department_grade_marks CHECK (marks >= 0 AND marks <= 100)
);

-- Attendance tracking table
CREATE TABLE IF NOT EXISTS attendance (
    attendance_id INT AUTO_INCREMENT PRIMARY KEY,
    student_id INT NOT NULL,
    course_code VARCHAR(20) NOT NULL,
    attendance_date DATE NOT NULL,
    status VARCHAR(10) NOT NULL,
    UNIQUE KEY uq_attendance (student_id, course_code, attendance_date),
    CONSTRAINT fk_attendance_student FOREIGN KEY (student_id) REFERENCES students(student_id),
    CONSTRAINT fk_attendance_course FOREIGN KEY (course_code) REFERENCES courses(course_code)
);

-- Users table with Role-Based Access Control (RBAC) and student record linkage
CREATE TABLE IF NOT EXISTS users (
    user_id INT AUTO_INCREMENT PRIMARY KEY,
    full_name VARCHAR(100) NOT NULL,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(150) NOT NULL UNIQUE,
    role VARCHAR(20) NOT NULL DEFAULT 'STUDENT',
    student_id INT NULL,
    password_hash CHAR(64) NOT NULL,
    salt CHAR(32) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_user_role CHECK (role IN ('ADMIN', 'STUDENT')),
    CONSTRAINT uq_users_student_id UNIQUE (student_id),
    CONSTRAINT fk_users_student FOREIGN KEY (student_id) 
        REFERENCES students(student_id) 
        ON DELETE SET NULL 
        ON UPDATE CASCADE
);

