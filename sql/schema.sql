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
