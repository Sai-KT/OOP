-- ============================================================================
-- Migration: 001_add_user_roles.sql
-- Description: Add Role-Based Access Control (ADMIN, STUDENT) and Student Link
-- Project: EduBridge Student Management System
-- Database: student_course_db
-- Target Table: users
-- ============================================================================

USE student_course_db;

-- ----------------------------------------------------------------------------
-- STEP 1: Add 'role' and 'student_id' columns with integrity constraints
-- ----------------------------------------------------------------------------
-- 1. 'role': VARCHAR(20) NOT NULL DEFAULT 'STUDENT'
--    - Enforces least-privilege: all existing and newly created users default to 'STUDENT'.
--    - Uses CHECK constraint 'chk_user_role' to restrict values to ('ADMIN', 'STUDENT').
--
-- 2. 'student_id': INT NULL
--    - Nullable: allows ADMIN accounts to exist without a linked student record.
--    - UNIQUE constraint 'uq_users_student_id': prevents multiple user accounts
--      from claiming/linking to the same student record. Multiple NULL values are
--      permitted by MySQL InnoDB unique indexes.
--    - FOREIGN KEY 'fk_users_student': enforces referential integrity to students(student_id).
-- ----------------------------------------------------------------------------

ALTER TABLE users
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'STUDENT' AFTER email,
    ADD COLUMN student_id INT NULL AFTER role,
    ADD CONSTRAINT chk_user_role CHECK (role IN ('ADMIN', 'STUDENT')),
    ADD CONSTRAINT uq_users_student_id UNIQUE (student_id),
    ADD CONSTRAINT fk_users_student FOREIGN KEY (student_id)
        REFERENCES students(student_id)
        ON DELETE SET NULL
        ON UPDATE CASCADE;

-- ----------------------------------------------------------------------------
-- STEP 2: Defensive Data Sanitization
-- ----------------------------------------------------------------------------
-- Explicitly guarantee all pre-existing records have role = 'STUDENT'.
-- Security requirement: Never automatically promote any existing account to ADMIN.
UPDATE users
SET role = 'STUDENT'
WHERE role IS NULL OR role NOT IN ('ADMIN', 'STUDENT');

-- ============================================================================
-- End of Migration 001_add_user_roles.sql
-- ============================================================================
