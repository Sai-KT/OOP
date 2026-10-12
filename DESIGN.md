# Case Study 1 — Analysis and Design

## Part A — Analysis

### 1. Objects
- Student S1
- Course C1
- Faculty F1
- Registration R1
- Department D1
- Semester Sem1

### 2. Classes
`Student`, `Course`, `Faculty`, `Registration`, `Department`, and `Semester` represent the objects above.

### 3. Attributes
- **Student:** `studentId`, `studentName`, `email`, `semester`, `departmentId`, `studentType`
- **Course:** `courseCode`, `courseName`, `credits`, `capacity`, `prerequisiteCode`
- **Faculty:** `facultyId`, `facultyName`, `departmentName`
- **Registration:** `student`, `course`, `status`
- **Department:** `departmentId`, `departmentName`
- **Semester:** `semesterNumber`, `academicYear`

### 4. Behaviors
- **Student:** `displayStudent()`, `getRegistrationGuidance()`
- **Course:** `displayCourseDetails()`
- **Faculty:** `displayFaculty()`
- **Registration:** `displayRegistration()`
- **Department:** getters and `setDepartmentName()`
- **Semester:** `displaySemester()`
- **StudentManager:** `addStudent()`, `loadStudents()`, `loadCourses()`, `registerCourse()`, `recordGrade()`, `calculateGpa()`, `displayRegistrations()`

### 5. Message passing
1. Student requests registration by entering student ID and course code.
2. `Main` sends the request to `StudentManager.registerCourse()`.
3. `StudentManager` checks Student and Course objects stored in maps.
4. It queries MySQL through JDBC to check duplicate enrollment, prerequisite grades, and course capacity.
5. If valid, it inserts a confirmed registration; if the course is full, it stores a waitlisted registration and adds the ID to a `Queue<Integer>`.
6. `Main` prints the confirmation or a custom exception message.

## Part B — Design

### Class diagram
```mermaid
classDiagram
  class Student { -int studentId; -String studentName; -String email; -int semester; -int departmentId; -String studentType; +displayStudent(); +getRegistrationGuidance() }
  class UndergraduateStudent
  class PostgraduateStudent
  class Course { -String courseCode; -String courseName; -int credits; -int capacity; -String prerequisiteCode; +displayCourseDetails() }
  class Faculty { -int facultyId; -String facultyName; -String departmentName; +displayFaculty() }
  class Registration { -Student student; -Course course; -String status; +displayRegistration() }
  class Department { -int departmentId; -String departmentName }
  class Semester { -int semesterNumber; -String academicYear; +displaySemester() }
  class StudentManager { +addStudent(); +registerCourse(); +recordGrade(); +calculateGpa() }
  Student <|-- UndergraduateStudent
  Student <|-- PostgraduateStudent
  Registration --> Student : registers
  Registration --> Course : selects
  StudentManager --> Student : manages
  StudentManager --> Course : manages
  Student --> Department : belongs to
  Student --> Semester : studies in
  Faculty --> Course : teaches
```

### Object interaction diagram
```mermaid
sequenceDiagram
  participant S as Student
  participant M as StudentManager
  participant C as Course
  participant DB as MySQL via JDBC
  S->>M: registerCourse(studentId, courseCode)
  M->>DB: Check duplicate registration
  DB-->>M: No duplicate
  M->>DB: Check prerequisite grade and seat count
  DB-->>M: Prerequisite/availability result
  alt prerequisite passed and seat available
    M->>DB: INSERT CONFIRMED registration
    DB-->>M: Success
    M-->>S: Registration confirmed
  else course full
    M->>DB: INSERT WAITLISTED registration
    M-->>S: Added to waitlist
  else invalid prerequisite
    M-->>S: PrerequisiteNotMetException
  end
```
