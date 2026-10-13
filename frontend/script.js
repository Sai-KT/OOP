"use strict";

// The Java WebServer serves this page, so the API is on the same address.
const API = "/api";
const PDFJS_URL = "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.min.js";
const PDFJS_WORKER = "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js";

let students = [];
let courses = [];
let enrollments = [];
let grades = [];
let attendance = [];
let departments = [];
let accounts = [];
let currentUser = null;
let toastTimer;

const $ = id => document.getElementById(id);

const esc = value => String(value ?? "").replace(/[&<>"']/g, char => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
}[char]));

const initials = name => String(name || "S").trim()
  .split(/\s+/).slice(0, 2).map(word => word[0] || "").join("").toUpperCase();

// Year of study is worked out from the semester: Sem 1-2 = 1st Year, 3-4 = 2nd Year, 5-6 = 3rd Year, 7-8 = 4th Year.
const YEAR_NAMES = ["1st Year", "2nd Year", "3rd Year", "4th Year"];
const yearNumber = semester => Math.min(4, Math.max(1, Math.ceil(Number(semester || 1) / 2)));
const yearLabel = semester => YEAR_NAMES[yearNumber(semester) - 1];
const studentById = id => students.find(student => Number(student.studentId) === Number(id));
const courseByCode = code => courses.find(course => course.courseCode === String(code || "").toUpperCase());
const today = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
};

/* API — GET returns JSON; POST sends simple form data (read by WebServer.java) */
async function api(path, data) {
  const options = data
    ? { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams(data) }
    : {};
  const response = await fetch(`${API}${path}`, options);
  let body = null;
  try { body = await response.json(); } catch {}
  if (response.status === 401 && !path.startsWith("/auth/")) {
    window.location.href = "/login.html"; // session expired or not logged in
    throw new Error("Please log in first.");
  }
  if (!response.ok) throw new Error(body?.message || `Request failed (${response.status})`);
  return body;
}

function toast(message) {
  const node = $("toast");
  node.textContent = message;
  node.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => node.classList.remove("show"), 3500);
}

function setMessage(id, message, error = false) {
  const node = $(id);
  if (!node) return;
  node.textContent = message;
  node.classList.toggle("error-text", error);
  node.classList.toggle("hidden", !message);
}

const openModal = id => $(id).classList.remove("hidden");
const closeModal = id => $(id).classList.add("hidden");

function pill(text, tone) {
  return `<span class="status-pill ${tone}">${esc(text)}</span>`;
}

const statusPill = status => String(status).toUpperCase() === "CONFIRMED"
  ? pill("Confirmed", "status-active")
  : pill("Waitlisted", "status-pending");

const yearPill = semester => pill(yearLabel(semester), "status-active");

const attendancePill = status => {
  const value = String(status || "").toUpperCase();
  return `<span class="status-attendance attendance-${value.toLowerCase()}">${esc(value[0] + value.slice(1).toLowerCase())}</span>`;
};

const percentPill = percent => percent === null
  ? `<span class="muted">—</span>`
  : pill(`${percent}%`, percent >= 75 ? "status-active" : "status-inactive");

function studentCell(student) {
  return `<div class="student-cell">
    <div class="student-avatar">${esc(initials(student.studentName))}</div>
    <div class="student-name"><strong>${esc(student.studentName)}</strong><span>${esc(student.email || "ID " + student.studentId)}</span></div>
  </div>`;
}

function csvDownload(filename, columns, rows) {
  const quote = value => `"${String(value ?? "").replace(/"/g, '""')}"`;
  const content = [
    columns.map(column => quote(column[0])).join(","),
    ...rows.map(row => columns.map(column => quote(column[1](row))).join(","))
  ].join("\r\n");
  const url = URL.createObjectURL(new Blob(["﻿", content], { type: "text/csv;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

/* ATTENDANCE MATHS — late counts as attended */

function attendancePercent(records) {
  if (!records.length) return null;
  const attended = records.filter(a => a.status !== "ABSENT").length;
  return Math.round(attended / records.length * 100);
}

const studentAttendance = id => attendancePercent(attendance.filter(a => Number(a.studentId) === Number(id)));

/* LOGIN / LOGOUT */

async function loadUser() {
  try {
    currentUser = await api("/auth/me");
  } catch {
    window.location.href = "/login.html";
    return false;
  }
  if (!currentUser) {
    window.location.href = "/login.html";
    return false;
  }
  if (currentUser.role === "STUDENT") {
    window.location.href = "/student.html";
    return false;
  }
  const name = currentUser.fullName || currentUser.username;
  $("userName").textContent = name;
  $("userAvatar").textContent = initials(name);
  if ($("userRoleBadge")) {
    $("userRoleBadge").textContent = currentUser.role || "ADMIN";
  }
  const hour = new Date().getHours();
  const part = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
  $("greeting").textContent = `${part}, ${name.split(" ")[0]} ✦`;
  return true;
}

$("logoutButton").addEventListener("click", async () => {
  try { await api("/auth/logout", {}); } catch {}
  window.location.href = "/login.html";
});

/* NAVIGATION */

const PAGES = ["overview", "students", "departments", "courses", "enrollments", "attendance", "grades", "reports", "accounts"];
const PAGE_TITLES = { enrollments: "Enrollment", grades: "Grades & GPA", departments: "Departments", accounts: "Student Accounts" };

function showPage(page) {
  PAGES.forEach(name => $(`${name}Page`).classList.toggle("hidden", name !== page));
  document.querySelectorAll(".nav-item").forEach(button =>
    button.classList.toggle("active", button.dataset.page === page));
  $("breadcrumb").textContent = PAGE_TITLES[page] || page[0].toUpperCase() + page.slice(1);
  $("sidebar").classList.remove("open");
}

document.querySelectorAll("[data-page]").forEach(button =>
  button.addEventListener("click", event => { event.preventDefault(); showPage(button.dataset.page); }));
document.querySelectorAll("[data-goto]").forEach(button =>
  button.addEventListener("click", () => showPage(button.dataset.goto)));
$("menuButton").addEventListener("click", () => $("sidebar").classList.toggle("open"));

/* LOAD DATA */

async function loadData() {
  const endpoints = [
    ["students", "/students"], ["courses", "/courses"], ["enrollments", "/enrollments"],
    ["grades", "/grades"], ["attendance", "/attendance"], ["departments", "/departments"],
    ["accounts", "/admin/accounts"]
  ];
  const failures = [];

  const results = await Promise.all(endpoints.map(async ([key, path]) => {
    try { return [key, await api(path)]; }
    catch (error) { failures.push(key); console.warn(`Could not load ${path}:`, error.message); return [key, null]; }
  }));

  for (const [key, value] of results) {
    if (!Array.isArray(value)) continue;
    if (key === "students") students = value.sort((a, b) => a.studentId - b.studentId);
    if (key === "courses") courses = value;
    if (key === "enrollments") enrollments = value;
    if (key === "grades") grades = value;
    if (key === "attendance") attendance = value;
    if (key === "departments") departments = value;
    if (key === "accounts") accounts = value;
  }

  renderAll();
  $("dashboardNote").textContent = failures.length
    ? `Some data could not load (${failures.join(", ")}). Is WebServer running and MySQL connected?`
    : `${students.length} students, ${courses.length} courses, ${enrollments.length} enrollments, ${attendance.length} attendance entries, ${grades.length} grades and ${accounts.length} user accounts loaded from MySQL.`;
}

// Each section is drawn separately, so an error in one section never blanks the others.
function renderAll() {
  [populateSelects, renderDashboard, renderStudents, renderDepartments, renderCourses,
   renderEnrollments, renderAttendance, renderGrades, renderAccounts].forEach(render => {
    try { render(); } catch (error) { console.error(`${render.name} failed:`, error); }
  });
}

/* DASHBOARD */

function renderDashboard() {
  const total = students.length;
  const confirmed = enrollments.filter(r => r.status === "CONFIRMED").length;
  const waitlisted = enrollments.filter(r => r.status === "WAITLISTED").length;
  const overall = attendancePercent(attendance);

  $("totalStudents").textContent = total;
  $("totalCourses").textContent = courses.length;
  $("totalConfirmed").textContent = confirmed;
  $("totalWaitlisted").textContent = waitlisted;
  $("overviewAttendance").textContent = overall === null ? "—" : `${overall}%`;
  $("navStudentCount").textContent = total;
  $("navEnrollmentCount").textContent = enrollments.length;

  $("yearBreakdown").innerHTML = total
    ? YEAR_NAMES.map((name, index) => {
        const count = students.filter(s => yearNumber(s.semester) === index + 1).length;
        return `<div class="year-row">
          <div class="year-row-label"><span>${name}</span><strong>${count}</strong></div>
          <div class="year-track"><span style="width:${count / total * 100}%"></span></div>
        </div>`;
      }).join("")
    : `<p class="muted">Add students to see the year distribution.</p>`;

  const departmentNames = departments.length
    ? departments.map(d => d.departmentName)
    : [...new Set(students.map(s => s.departmentName))];
  $("departmentOverview").innerHTML = departmentNames.map(name => {
    const count = students.filter(s => s.departmentName === name).length;
    return `<div class="status-summary-row">${pill(name, "status-active")}<strong>${count}</strong><span>${total ? Math.round(count / total * 100) : 0}%</span></div>`;
  }).join("");

  const recent = [...students].reverse().slice(0, 5);
  $("recentStudentsBody").innerHTML = recent.map(student => `
    <tr>
      <td>${studentCell(student)}</td>
      <td>${esc(student.studentId)}</td>
      <td>${esc(student.departmentName || student.departmentId)}</td>
      <td>${yearPill(student.semester)}</td>
      <td>Semester ${esc(student.semester)}</td>
    </tr>`).join("");
  $("recentEmpty").classList.toggle("hidden", recent.length > 0);
}

/* STUDENTS */

function renderStudents() {
  const query = ($("studentSearch").value || "").trim().toLowerCase();
  const year = $("yearFilter").value;

  const rows = students.filter(student =>
    [student.studentId, student.studentName, student.email, student.departmentName, student.semester, yearLabel(student.semester)]
      .join(" ").toLowerCase().includes(query) &&
    (year === "all" || yearNumber(student.semester) === Number(year)));

  $("studentsBody").innerHTML = rows.map(student => `
    <tr>
      <td>${studentCell(student)}</td>
      <td>${esc(student.studentId)}</td>
      <td>${esc(student.departmentName || student.departmentId)}</td>
      <td>${yearPill(student.semester)}</td>
      <td>Semester ${esc(student.semester)}</td>
      <td><strong>${Number(student.gpa || 0).toFixed(2)}</strong></td>
      <td>${percentPill(studentAttendance(student.studentId))}</td>
    </tr>`).join("");
  $("studentsEmpty").classList.toggle("hidden", rows.length > 0);
}

$("studentSearch").addEventListener("input", renderStudents);
$("yearFilter").addEventListener("change", renderStudents);
$("globalSearch").addEventListener("input", event => {
  $("studentSearch").value = event.target.value;
  showPage("students");
  renderStudents();
});

/* ADD STUDENT */

function openStudentForm() {
  $("studentForm").reset();
  setMessage("studentFormError", "");
  populateSelects();
  fillSemesterOptions();
  openModal("studentModal");
}

// After a Year is chosen, show only its two semesters (1st Year -> Semester 1 or 2, ...).
function fillSemesterOptions() {
  const year = Number($("newStudentYear").value);
  $("newStudentSemester").innerHTML = year
    ? [year * 2 - 1, year * 2].map(sem => `<option value="${sem}">Semester ${sem}</option>`).join("")
    : `<option value="">Select year first</option>`;
}

$("newStudentYear").addEventListener("change", fillSemesterOptions);

$("addStudentButton").addEventListener("click", openStudentForm);
$("addStudentTop").addEventListener("click", openStudentForm);
$("closeStudentModal").addEventListener("click", () => closeModal("studentModal"));
$("cancelStudentModal").addEventListener("click", () => closeModal("studentModal"));

$("studentForm").addEventListener("submit", async event => {
  event.preventDefault();
  try {
    const result = await api("/students", {
      studentId: $("newStudentId").value.trim(),
      studentName: $("newStudentName").value.trim(),
      email: $("newStudentEmail").value.trim(),
      semester: $("newStudentSemester").value,
      departmentId: $("newStudentDepartment").value,
      studentType: "UNDERGRADUATE" // the backend needs a type; Year is shown on the dashboard instead
    });
    closeModal("studentModal");
    await loadData();
    toast(result.message || "Student saved.");
  } catch (error) {
    setMessage("studentFormError", error.message, true);
  }
});

/* DEPARTMENTS — directory, add department, assign students */

function renderDepartments() {
  $("departmentsBody").innerHTML = departments.map((dept, index) => {
    const members = students.filter(s => Number(s.departmentId) === Number(dept.departmentId));
    const detailId = `departmentStudents${index}`;
    const cards = members.map(s => `
      <div class="course-student" style="margin:0">
        <div class="student-avatar">${esc(initials(s.studentName))}</div>
        <div class="course-student-info"><strong>${esc(s.studentName)}</strong><span>ID ${esc(s.studentId)} · ${esc(yearLabel(s.semester))}</span></div>
      </div>`).join("") || `<p class="muted" style="margin:0">No students in this department yet.</p>`;
    return `
    <tr>
      <td><strong>${esc(dept.departmentId)}</strong></td>
      <td>${esc(dept.departmentName)}</td>
      <td>${pill(`${members.length} student${members.length === 1 ? "" : "s"}`, "status-active")}</td>
      <td class="action-cell" style="white-space:nowrap">
        <button type="button" class="small-action" data-toggle-dept="${detailId}">View students</button>
        <button type="button" class="small-action" data-assign-dept="${esc(dept.departmentId)}">Assign student</button>
      </td>
    </tr>
    <tr id="${detailId}" class="hidden">
      <td colspan="4" style="background:#fbfaff;padding:16px 18px">
        <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:10px">${cards}</div>
      </td>
    </tr>`;
  }).join("");
  $("departmentsEmpty").classList.toggle("hidden", departments.length > 0);

  $("departmentsBody").querySelectorAll("[data-toggle-dept]").forEach(button =>
    button.addEventListener("click", () => {
      const open = $(button.dataset.toggleDept).classList.toggle("hidden") === false;
      button.textContent = open ? "Hide students" : "View students";
    }));
  $("departmentsBody").querySelectorAll("[data-assign-dept]").forEach(button =>
    button.addEventListener("click", () => openAssignForm(button.dataset.assignDept)));
}

$("addDepartmentButton").addEventListener("click", () => {
  $("departmentForm").reset();
  setMessage("departmentFormError", "");
  $("newDepartmentId").value = departments.reduce((max, d) => Math.max(max, Number(d.departmentId)), 0) + 1;
  openModal("departmentModal");
  $("newDepartmentName").focus();
});
$("closeDepartmentModal").addEventListener("click", () => closeModal("departmentModal"));
$("cancelDepartmentModal").addEventListener("click", () => closeModal("departmentModal"));

$("departmentForm").addEventListener("submit", async event => {
  event.preventDefault();
  const name = $("newDepartmentName").value.trim();
  if (departments.some(d => d.departmentName.toLowerCase() === name.toLowerCase())) {
    return setMessage("departmentFormError", "A department with this name already exists.", true);
  }
  try {
    const result = await api("/departments", { departmentId: $("newDepartmentId").value.trim(), departmentName: name });
    closeModal("departmentModal");
    await loadData();
    toast(result.message || "Department saved.");
  } catch (error) {
    setMessage("departmentFormError", error.message, true);
  }
});

function updateAssignHint() {
  const student = studentById($("assignStudent").value);
  $("assignHint").textContent = student ? `Current department: ${student.departmentName || "None"}` : "";
}

function openAssignForm(departmentId = "") {
  $("assignForm").reset();
  setMessage("assignError", "");
  populateSelects();
  if (departmentId) $("assignDepartment").value = departmentId;
  updateAssignHint();
  openModal("assignModal");
}

$("assignStudent").addEventListener("change", updateAssignHint);
$("closeAssignModal").addEventListener("click", () => closeModal("assignModal"));
$("cancelAssignModal").addEventListener("click", () => closeModal("assignModal"));

$("assignForm").addEventListener("submit", async event => {
  event.preventDefault();
  try {
    const result = await api("/departments", {
      action: "assign",
      studentId: $("assignStudent").value,
      departmentId: $("assignDepartment").value
    });
    closeModal("assignModal");
    await loadData();
    toast(result.message || "Student assigned.");
  } catch (error) {
    setMessage("assignError", error.message, true);
  }
});

/* COURSES — each course with its enrolled students */

function renderCourses() {
  $("courseCount").textContent = courses.length;
  $("courseCapacity").textContent = courses.reduce((sum, c) => sum + Number(c.capacity || 0), 0);
  $("seatsUsed").textContent = courses.reduce((sum, c) => sum + Number(c.confirmed || 0), 0);

  $("coursesBody").innerHTML = courses.map((course, index) => {
    const used = Number(course.confirmed || 0);
    const capacity = Number(course.capacity || 0);
    const percent = capacity ? Math.min(100, Math.round(used / capacity * 100)) : 0;
    const courseEnrollments = enrollments.filter(r => r.courseCode === course.courseCode);
    const detailId = `courseStudents${index}`;

    const cards = courseEnrollments.map(reg => `
      <div class="course-student" style="margin:0">
        <div class="student-avatar">${esc(initials(reg.studentName))}</div>
        <div class="course-student-info"><strong>${esc(reg.studentName)}</strong><span>ID ${esc(reg.studentId)}</span></div>
        ${statusPill(reg.status)}
      </div>`).join("") || `<p class="muted" style="margin:0">No students enrolled yet.</p>`;

    return `
    <tr>
      <td><strong>${esc(course.courseCode)}</strong></td>
      <td>
        <div style="font-weight:650">${esc(course.courseName)}</div>
        ${pill(`${courseEnrollments.length} student${courseEnrollments.length === 1 ? "" : "s"}`, "status-active")}
        ${course.waitlisted ? pill(`${course.waitlisted} waitlisted`, "status-pending") : ""}
      </td>
      <td>${esc(course.credits)}</td>
      <td style="min-width:130px">
        <div style="display:flex;justify-content:space-between;font-size:12px;margin-bottom:6px"><strong>${used}/${capacity}</strong><span class="muted">${used >= capacity ? "Full" : `${percent}%`}</span></div>
        <div class="year-track"><span style="width:${percent}%"></span></div>
      </td>
      <td>${esc(course.prerequisiteCode || "None")}</td>
      <td class="action-cell" style="white-space:nowrap">
        <button type="button" class="small-action" data-toggle-students="${detailId}">View students</button>
        <button type="button" class="small-action" data-enroll-course="${esc(course.courseCode)}">Enroll</button>
      </td>
    </tr>
    <tr id="${detailId}" class="hidden">
      <td colspan="6" style="background:#fbfaff;padding:16px 18px">
        <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:10px">${cards}</div>
      </td>
    </tr>`;
  }).join("");
  $("coursesEmpty").classList.toggle("hidden", courses.length > 0);

  $("coursesBody").querySelectorAll("[data-toggle-students]").forEach(button =>
    button.addEventListener("click", () => {
      const open = $(button.dataset.toggleStudents).classList.toggle("hidden") === false;
      button.textContent = open ? "Hide students" : "View students";
    }));
  $("coursesBody").querySelectorAll("[data-enroll-course]").forEach(button =>
    button.addEventListener("click", () => openEnrollmentForm(button.dataset.enrollCourse)));
}

/* SELECT OPTIONS */

function fillSelect(id, placeholder, items, placeholderValue = "") {
  const element = $(id);
  const previous = element.value;
  element.innerHTML = `<option value="${placeholderValue}">${placeholder}</option>` +
    items.map(([value, label]) => `<option value="${esc(value)}">${esc(label)}</option>`).join("");
  if ([...element.options].some(option => option.value === previous)) element.value = previous;
}

function populateSelects() {
  const studentItems = students.map(s => [s.studentId, `${s.studentId} — ${s.studentName}`]);
  const courseItems = courses.map(c => [c.courseCode, `${c.courseCode} — ${c.courseName}`]);
  ["enrollmentStudent", "gradeStudent", "gpaStudent", "attendanceStudent", "assignStudent"].forEach(id => fillSelect(id, "Select student", studentItems));
  ["enrollmentCourse", "attendanceCourse"].forEach(id => fillSelect(id, "Select course", courseItems));
  const departmentItems = departments.map(d => [d.departmentId, `${d.departmentId} — ${d.departmentName}`]);
  ["gradeDepartment", "assignDepartment"].forEach(id => fillSelect(id, "Select department", departmentItems));
  fillSelect("attendanceCourseFilter", "All courses", courseItems, "all");
  fillSelect("newStudentDepartment", "Select department", departments.map(d => [d.departmentId, d.departmentName]));
}

/* ENROLLMENT */

function renderEnrollments() {
  const query = ($("enrollmentSearch").value || "").toLowerCase();
  const filter = $("enrollmentStatusFilter").value;

  $("confirmedCount").textContent = enrollments.filter(r => r.status === "CONFIRMED").length;
  $("waitlistCount").textContent = enrollments.filter(r => r.status === "WAITLISTED").length;
  $("enrollmentTotal").textContent = enrollments.length;

  const rows = enrollments.filter(reg =>
    [reg.registrationId, reg.studentId, reg.studentName, reg.courseCode, reg.courseName].join(" ").toLowerCase().includes(query) &&
    (filter === "all" || reg.status === filter));

  $("enrollmentsBody").innerHTML = rows.map(reg => `
    <tr>
      <td>#${esc(reg.registrationId)}</td>
      <td>${esc(reg.studentName)}<span class="table-sub">ID ${esc(reg.studentId)}</span></td>
      <td>${esc(reg.courseCode)}<span class="table-sub">${esc(reg.courseName)}</span></td>
      <td>${statusPill(reg.status)}</td>
      <td>${esc(String(reg.registrationDate || "").slice(0, 16))}</td>
    </tr>`).join("");
  $("enrollmentsEmpty").classList.toggle("hidden", rows.length > 0);
}

$("enrollmentSearch").addEventListener("input", renderEnrollments);
$("enrollmentStatusFilter").addEventListener("change", renderEnrollments);

function updateEnrollmentHint() {
  const course = courseByCode($("enrollmentCourse").value);
  $("enrollmentHint").textContent = course
    ? `Seats: ${course.confirmed}/${course.capacity}${course.confirmed >= course.capacity ? " · Course is full — the student will be waitlisted." : " · No grade/prerequisite is required."}`
    : "";
}

function openEnrollmentForm(courseCode = "") {
  $("enrollmentForm").reset();
  setMessage("enrollmentError", "");
  populateSelects();
  if (courseCode) $("enrollmentCourse").value = courseCode;
  updateEnrollmentHint();
  openModal("enrollmentModal");
}

$("enrollmentCourse").addEventListener("change", updateEnrollmentHint);
$("addEnrollmentButton").addEventListener("click", () => openEnrollmentForm());
$("closeEnrollmentModal").addEventListener("click", () => closeModal("enrollmentModal"));
$("cancelEnrollmentModal").addEventListener("click", () => closeModal("enrollmentModal"));

$("enrollmentForm").addEventListener("submit", async event => {
  event.preventDefault();
  try {
    const result = await api("/enrollments", {
      studentId: $("enrollmentStudent").value,
      courseCode: $("enrollmentCourse").value
    });
    closeModal("enrollmentModal");
    await loadData();
    toast(result.status === "WAITLISTED" ? `Waitlisted: ${result.message}` : result.message);
  } catch (error) {
    setMessage("enrollmentError", error.message, true);
  }
});

/* ATTENDANCE */

function renderAttendance() {
  const overall = attendancePercent(attendance);
  const todayCount = attendance.filter(a => a.date === today()).length;

  // Summary: one row per student + course pair
  const groups = new Map();
  for (const a of attendance) {
    const key = `${a.studentId}|${a.courseCode}`;
    if (!groups.has(key)) groups.set(key, { studentId: a.studentId, studentName: a.studentName, courseCode: a.courseCode, courseName: a.courseName, records: [] });
    groups.get(key).records.push(a);
  }
  const summary = [...groups.values()].sort((a, b) => a.studentId - b.studentId || a.courseCode.localeCompare(b.courseCode));
  const low = summary.filter(g => attendancePercent(g.records) < 75).length;

  $("attendanceTotal").textContent = attendance.length;
  $("attendanceRate").textContent = overall === null ? "—" : `${overall}%`;
  $("attendanceToday").textContent = todayCount;
  $("attendanceTodayDate").textContent = today();
  $("attendanceLow").textContent = low;

  $("attendanceSummaryBody").innerHTML = summary.map(g => {
    const count = status => g.records.filter(r => r.status === status).length;
    return `<tr>
      <td>${esc(g.studentName)}<span class="table-sub">ID ${esc(g.studentId)}</span></td>
      <td>${esc(g.courseCode)}<span class="table-sub">${esc(g.courseName)}</span></td>
      <td>${count("PRESENT")}</td>
      <td>${count("LATE")}</td>
      <td>${count("ABSENT")}</td>
      <td><strong>${g.records.length}</strong></td>
      <td>${percentPill(attendancePercent(g.records))}</td>
    </tr>`;
  }).join("");
  $("attendanceSummaryEmpty").classList.toggle("hidden", summary.length > 0);

  // History with filters
  const query = ($("attendanceSearch").value || "").toLowerCase();
  const course = $("attendanceCourseFilter").value || "all";
  const status = $("attendanceStatusFilter").value;
  const date = $("attendanceDateFilter").value;
  const rows = attendance.filter(a =>
    [a.studentId, a.studentName, a.courseCode, a.courseName].join(" ").toLowerCase().includes(query) &&
    (course === "all" || a.courseCode === course) &&
    (status === "all" || a.status === status) &&
    (!date || a.date === date));

  $("attendanceBody").innerHTML = rows.map(a => `
    <tr>
      <td>${esc(a.date)}</td>
      <td>${esc(a.studentName)}<span class="table-sub">ID ${esc(a.studentId)}</span></td>
      <td>${esc(a.courseCode)}<span class="table-sub">${esc(a.courseName)}</span></td>
      <td>${attendancePill(a.status)}</td>
    </tr>`).join("");
  $("attendanceEmpty").classList.toggle("hidden", rows.length > 0);
}

["attendanceSearch", "attendanceCourseFilter", "attendanceStatusFilter", "attendanceDateFilter"].forEach(id =>
  $(id).addEventListener(id === "attendanceSearch" ? "input" : "change", renderAttendance));

/* GRADES AND GPA — department-based */
function renderGrades() {
  $("gradesBody").innerHTML = grades.map(grade => `
    <tr>
      <td>${esc(grade.studentName)}<span class="table-sub">ID ${esc(grade.studentId)}</span></td>
      <td>${esc(grade.departmentName)}</td>
      <td><strong>${Number(grade.marks).toFixed(2)}</strong></td>
      <td>${Number(grade.marks) >= 40 ? pill("Pass", "status-active") : pill("Fail", "status-inactive")}</td>
    </tr>`).join("");
  $("gradesEmpty").classList.toggle("hidden", grades.length > 0);
}

$("gradeForm").addEventListener("submit", async event => {
  event.preventDefault();
  try {
    const result = await api("/grades", {
      studentId: $("gradeStudent").value,
      departmentId: $("gradeDepartment").value,
      marks: $("gradeMarks").value
    });
    setMessage("gradeMessage", `Grade saved. GPA is now ${Number(result.gpa).toFixed(2)}.`);
    $("gradeMarks").value = "";
    await loadData();
  } catch (error) { setMessage("gradeMessage", error.message, true); }
});

$("gpaForm").addEventListener("submit", async event => {
  event.preventDefault();
  const result = $("gpaResult");
  result.classList.remove("hidden");
  result.textContent = "Calculating GPA...";
  try {
    const data = await api(`/gpa?studentId=${encodeURIComponent($("gpaStudent").value)}`);
    result.innerHTML = `<span>Calculated GPA</span><strong>${Number(data.gpa).toFixed(2)}</strong><small>Average department marks ÷ 10 (10-point scale)</small>`;
  } catch (error) { result.textContent = error.message; }
});
$("refreshGradesButton").addEventListener("click", loadData);

/* SINGLE-STUDENT ATTENDANCE FORM */
$("attendanceDate").value = today();
$("attendanceDate").max = today();
$("attendanceForm").addEventListener("submit", async event => {
  event.preventDefault();
  try {
    const result = await api("/attendance", {
      studentId: $("attendanceStudent").value,
      date: $("attendanceDate").value,
      courseCode: $("attendanceCourse").value,
      status: $("attendanceStatus").value
    });
    setMessage("attendanceMessage", result.message || "Attendance saved successfully.");
    await loadData();
  } catch (error) { setMessage("attendanceMessage", error.message, true); }
});

/* ================================================================
   PDF IMPORT — students, enrollment, attendance and grades
   1. Read the PDF in the browser with pdf.js (text lines).
   2. Each line is matched to a row (header lines are skipped).
   3. Preview -> "Import records" sends every row to the same Java API
      used by the normal forms, so all backend rules still apply.
   ================================================================ */

function parseDepartmentGradeLine(line) {
  const text = String(line || "").trim();
  const values = text.split(/[|,;\t]+/).map(v => v.trim()).filter(Boolean);
  // Skip titles and header rows: a data row starts with two whole numbers (student ID, department ID).
  if (values.length < 3 || !/^\d+$/.test(values[0]) || !/^\d+$/.test(values[1])) return null;
  const row = { studentId: Number(values[0]), departmentId: Number(values[1]), marks: Number(values[2]) };
  if (!studentById(row.studentId)) row.error = "Student not found";
  else if (!departments.some(d => Number(d.departmentId) === row.departmentId)) row.error = "Department not found";
  else if (!Number.isFinite(row.marks) || row.marks < 0 || row.marks > 100) row.error = "Marks must be between 0 and 100";
  return row;
}

const EMAIL_RE = /[\w.+-]+@[\w-]+(?:\.[\w-]+)+/;
const COURSE_RE = /\b([A-Za-z]{2,5})\s?(\d{2,4}[A-Za-z]?)\b/g;

const IMPORTS = {
  students: {
    title: "Import students from PDF",
    help: "One student per row: Student ID | Full name | Email | Year | Semester | Department. Year is optional (worked out from the semester).",
    columns: ["STUDENT ID", "NAME", "EMAIL", "YEAR", "SEMESTER", "DEPARTMENT"],
    cells: r => [r.studentId, r.studentName, r.email, r.semester ? yearLabel(r.semester) : "", r.semester, r.departmentName],
    parse: parseStudentLine,
    save: r => api("/students", { studentId: r.studentId, studentName: r.studentName, email: r.email, semester: r.semester, departmentId: r.departmentId, studentType: "UNDERGRADUATE" })
  },
  enrollments: {
    title: "Import enrollment from PDF",
    help: "One enrollment per row: Student ID | Course code (e.g. 101 | IT101). Students and courses must already exist.",
    columns: ["STUDENT ID", "STUDENT", "COURSE"],
    cells: r => [r.studentId, studentById(r.studentId)?.studentName || "", r.courseCode],
    parse: parseEnrollmentLine,
    save: r => api("/enrollments", { studentId: r.studentId, courseCode: r.courseCode })
  },
  attendance: {
    title: "Import attendance from PDF",
    help: "One entry per row: Student ID | Course code | Date (YYYY-MM-DD or DD-MM-YYYY) | Present / Absent / Late (P / A / L). Enrollment grade or prerequisite is not required.",
    columns: ["STUDENT ID", "STUDENT", "COURSE", "DATE", "STATUS"],
    cells: r => [r.studentId, studentById(r.studentId)?.studentName || "", r.courseCode, r.date, r.status],
    parse: parseAttendanceLine,
    save: r => api("/attendance", { studentId: r.studentId, courseCode: r.courseCode, date: r.date, status: r.status })
  },
  grades: {
    title: "Import department grades from PDF",
    help: "One grade per row: Student ID | Department ID | Marks (0–100), e.g. 101 | 1 | 78.",
    columns: ["STUDENT ID", "STUDENT", "DEPARTMENT ID", "MARKS"],
    cells: r => [r.studentId, studentById(r.studentId)?.studentName || "", r.departmentId, r.marks],
    parse: parseDepartmentGradeLine,
    save: r => api("/grades", { studentId: r.studentId, departmentId: r.departmentId, marks: r.marks })
  }
};

let importType = "students";
let importRows = [];

// Load pdf.js only when it is needed.
function loadPdfJs() {
  if (window.pdfjsLib) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = PDFJS_URL;
    script.onload = () => { pdfjsLib.GlobalWorkerOptions.workerSrc = PDFJS_WORKER; resolve(); };
    script.onerror = () => reject(new Error("PDF reader could not load. Check your internet connection."));
    document.head.appendChild(script);
  });
}

// Returns the text of the file as lines. PDF table cells are joined with " | ".
async function readLines(file) {
  const isPdf = /\.pdf$/i.test(file.name) || file.type === "application/pdf";
  if (!isPdf) return (await file.text()).split(/\r?\n/);

  await loadPdfJs();
  const pdf = await pdfjsLib.getDocument({ data: new Uint8Array(await file.arrayBuffer()) }).promise;
  const lines = [];
  for (let pageNumber = 1; pageNumber <= pdf.numPages; pageNumber++) {
    const page = await pdf.getPage(pageNumber);
    const content = await page.getTextContent();
    const rows = [];
    for (const item of content.items) {
      if (!item.str || !item.str.trim()) continue;
      const x = item.transform[4];
      const y = item.transform[5];
      let row = rows.find(r => Math.abs(r.y - y) < 4);
      if (!row) rows.push(row = { y, items: [] });
      row.items.push({ x, text: item.str.trim() });
    }
    rows.sort((a, b) => b.y - a.y); // top of the page first
    rows.forEach(row => lines.push(row.items.sort((a, b) => a.x - b.x).map(i => i.text).join(" | ")));
  }
  return lines;
}

const clean = line => String(line).replace(/[|,;\t]+/g, " ").replace(/\s+/g, " ").trim();

// Finds the first course code in the text, preferring one that exists.
function findCourse(text) {
  const matches = [...text.matchAll(COURSE_RE)].map(m => ({ code: (m[1] + m[2]).toUpperCase(), index: m.index, end: m.index + m[0].length }));
  return matches.find(m => courseByCode(m.code)) || matches[0] || null;
}

// Finds the student ID among the numbers before the course code (handles a serial-number column).
function findStudentId(text) {
  const numbers = text.match(/\b\d+\b/g) || [];
  return numbers.find(n => studentById(n)) || numbers[numbers.length - 1] || null;
}

function parseStudentLine(line) {
  const text = clean(line);
  const email = text.match(EMAIL_RE);
  if (!email) return null;
  let head = text.slice(0, email.index).trim();
  let rest = text.slice(email.index + email[0].length).trim();

  const headMatch = head.match(/^(?:\d+\s+)?(\d+)\s+(.+)$/);
  if (!headMatch) return null;
  const studentId = headMatch[1];
  const studentName = headMatch[2].replace(/^\d+\s+/, "").trim();

  // Year and semester: "3rd Year", "Year 3", "Sem 5", "Semester 5" or plain numbers.
  let year = null;
  let semester = null;
  const yearMatch = rest.match(/(\d)\s*(?:st|nd|rd|th)?\s*year|year\s*(\d)/i);
  if (yearMatch) { year = Number(yearMatch[1] || yearMatch[2]); rest = rest.replace(yearMatch[0], " "); }
  const semMatch = rest.match(/sem(?:ester)?\s*(\d)/i);
  if (semMatch) { semester = Number(semMatch[1]); rest = rest.replace(semMatch[0], " "); }

  const numbers = (rest.match(/\b\d+\b/g) || []).map(Number);
  rest = rest.replace(/\b\d+\b/g, " ").replace(/\s+/g, " ").trim();
  if (!year && !semester && numbers.length >= 2 && numbers[0] <= 4 && numbers[1] <= 8) { year = numbers.shift(); semester = numbers.shift(); }
  else if (!semester && numbers.length && numbers[0] <= 8 && (!year || numbers[0] > 0)) semester = numbers.shift();
  if (!semester && year) semester = year * 2 - 1;

  // Department by name, short form (IT, CE) or ID.
  const words = rest.toLowerCase();
  const shortName = d => d.departmentName.split(/\s+/).map(w => w[0]).join("").toLowerCase();
  let department = departments.find(d => words && (words.includes(d.departmentName.toLowerCase()) || d.departmentName.toLowerCase().includes(words)))
    || departments.find(d => words && words.split(" ").includes(shortName(d)))
    || departments.find(d => numbers.includes(Number(d.departmentId)));

  const row = { studentId, studentName, email: email[0], semester, departmentId: department?.departmentId, departmentName: department?.departmentName || rest || "?" };
  if (!semester || semester < 1 || semester > 8) row.error = "Semester must be 1–8";
  else if (year && yearNumber(semester) !== year) row.error = `Semester ${semester} is not in ${YEAR_NAMES[year - 1]}`;
  else if (!department) row.error = "Department not found";
  else if (studentById(studentId)) row.error = "Student ID already exists";
  else if (students.some(s => s.email.toLowerCase() === row.email.toLowerCase())) row.error = "Email already exists";
  return row;
}

function parseEnrollmentLine(line) {
  const text = clean(line);
  const course = findCourse(text);
  if (!course) return null;
  const studentId = findStudentId(text.slice(0, course.index));
  if (!studentId) return null;
  const row = { studentId, courseCode: course.code };
  if (!studentById(studentId)) row.error = "Student not found";
  else if (!courseByCode(course.code)) row.error = "Course not found";
  else if (enrollments.some(r => Number(r.studentId) === Number(studentId) && r.courseCode === course.code)) row.error = "Already enrolled";
  return row;
}

function parseGradeLine(line) {
  const text = clean(line);
  const course = findCourse(text);
  if (!course) return null;
  const studentId = findStudentId(text.slice(0, course.index));
  const marksList = text.slice(course.end).match(/\d{1,3}(?:\.\d+)?/g);
  if (!studentId || !marksList) return null;
  const row = { studentId, courseCode: course.code, marks: marksList[marksList.length - 1] };
  if (!studentById(studentId)) row.error = "Student not found";
  else if (!courseByCode(course.code)) row.error = "Course not found";
  else if (Number(row.marks) < 0 || Number(row.marks) > 100) row.error = "Marks must be 0–100";
  return row;
}

function parseAttendanceLine(line) {
  const text = clean(line);
  const course = findCourse(text);
  if (!course) return null;
  const studentId = findStudentId(text.slice(0, course.index));
  if (!studentId) return null;
  let rest = text.slice(course.end);

  let date = $("importDate").value;
  const iso = rest.match(/(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/);
  const dmy = rest.match(/(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})/);
  if (iso) { date = `${iso[1]}-${iso[2].padStart(2, "0")}-${iso[3].padStart(2, "0")}`; rest = rest.replace(iso[0], " "); }
  else if (dmy) { date = `${dmy[3]}-${dmy[2].padStart(2, "0")}-${dmy[1].padStart(2, "0")}`; rest = rest.replace(dmy[0], " "); }

  const statusMatch = rest.match(/\b(present|absent|late|p|a|l)\b/i);
  const status = statusMatch ? { p: "PRESENT", a: "ABSENT", l: "LATE" }[statusMatch[1][0].toLowerCase()] : "";
  const row = { studentId, courseCode: course.code, date, status };
  const enrolled = enrollments.find(r => Number(r.studentId) === Number(studentId) && r.courseCode === course.code);
  if (!status) row.error = "Status missing (Present/Absent/Late)";
  else if (!date) row.error = "Date missing";
  else if (date > today()) row.error = "Future date";
  else if (!studentById(studentId)) row.error = "Student not found";
  else if (!enrolled) row.error = "Not enrolled in course";
  else if (enrolled.status !== "CONFIRMED") row.error = "Only waitlisted";
  return row;
}

function renderImportPreview() {
  const config = IMPORTS[importType];
  $("importHead").innerHTML = `<tr><th>#</th>${config.columns.map(c => `<th>${c}</th>`).join("")}<th>CHECK</th></tr>`;
  $("importBody").innerHTML = importRows.map((row, index) => `
    <tr>
      <td>${index + 1}</td>
      ${config.cells(row).map(value => `<td>${esc(value)}</td>`).join("")}
      <td id="importStatus${index}">${row.result || (row.error ? pill(row.error, "status-inactive") : pill("Ready", "status-active"))}</td>
    </tr>`).join("");
  $("importPreviewWrap").classList.toggle("hidden", importRows.length === 0);
}

function openImport(type) {
  importType = type;
  importRows = [];
  const config = IMPORTS[type];
  $("importTitle").textContent = config.title;
  $("importHelp").textContent = config.help;
  $("importDateWrap").classList.toggle("hidden", type !== "attendance");
  $("importDate").value = today();
  $("importDate").max = today();
  $("importFile").value = "";
  $("saveImport").disabled = true;
  $("importPreviewWrap").classList.add("hidden");
  setMessage("importMessage", "Choose a file to start.");
  openModal("importModal");
}

async function readImportFile() {
  const file = $("importFile").files[0];
  if (!file) return;
  $("saveImport").disabled = true;
  setMessage("importMessage", "Reading file...");
  try {
    const lines = await readLines(file);
    const config = IMPORTS[importType];
    importRows = lines.map(line => config.parse(line)).filter(Boolean);
    renderImportPreview();
    if (!importRows.length) {
      return setMessage("importMessage", "No matching rows found. Check the row format above. Scanned (image) PDFs need OCR first.", true);
    }
    const ready = importRows.filter(r => !r.error).length;
    setMessage("importMessage", `${importRows.length} row(s) found — ${ready} ready, ${importRows.length - ready} with problems (these will be skipped).`);
    $("saveImport").disabled = ready === 0;
  } catch (error) {
    setMessage("importMessage", `Could not read file: ${error.message}`, true);
  }
}

$("importFile").addEventListener("change", readImportFile);
$("importDate").addEventListener("change", () => { if ($("importFile").files[0] && importType === "attendance") readImportFile(); });

$("saveImport").addEventListener("click", async () => {
  const config = IMPORTS[importType];
  $("saveImport").disabled = true;
  let saved = 0, failed = 0;
  for (let index = 0; index < importRows.length; index++) {
    const row = importRows[index];
    if (row.error || row.done) continue;
    $(`importStatus${index}`).innerHTML = pill("Saving...", "status-pending");
    try {
      const result = await config.save(row);
      row.done = true;
      row.result = result?.status === "WAITLISTED" ? pill("Waitlisted", "status-pending") : pill("✓ Saved", "status-active");
      saved++;
    } catch (error) {
      row.error = error.message;
      row.result = pill(error.message, "status-inactive");
      failed++;
    }
    $(`importStatus${index}`).innerHTML = row.result;
  }
  await loadData();
  setMessage("importMessage", `Import finished: ${saved} saved, ${failed} failed, ${importRows.filter(r => r.error).length - failed} skipped.`, failed > 0);
  toast(`${saved} record(s) imported.`);
});

document.querySelectorAll("[data-import]").forEach(button =>
  button.addEventListener("click", () => openImport(button.dataset.import)));
$("closeImport").addEventListener("click", () => closeModal("importModal"));
$("cancelImport").addEventListener("click", () => closeModal("importModal"));

/* REPORTS */

function exportStudents() {
  csvDownload("campusflow-students.csv", [
    ["Student ID", s => s.studentId], ["Name", s => s.studentName], ["Email", s => s.email],
    ["Department", s => s.departmentName], ["Year", s => yearLabel(s.semester)],
    ["Semester", s => s.semester], ["GPA", s => s.gpa],
    ["Attendance %", s => studentAttendance(s.studentId) ?? ""]
  ], students);
}

$("exportStudentsButton").addEventListener("click", exportStudents);
$("reportStudentsButton").addEventListener("click", exportStudents);
$("reportEnrollmentsButton").addEventListener("click", () => csvDownload("campusflow-enrollment.csv", [
  ["Enrollment ID", r => r.registrationId], ["Student ID", r => r.studentId], ["Student", r => r.studentName],
  ["Course code", r => r.courseCode], ["Course", r => r.courseName], ["Status", r => r.status], ["Date", r => r.registrationDate]
], enrollments));
$("reportAttendanceButton").addEventListener("click", () => csvDownload("campusflow-attendance.csv", [
  ["Date", a => a.date], ["Student ID", a => a.studentId], ["Student", a => a.studentName],
  ["Course code", a => a.courseCode], ["Course", a => a.courseName], ["Status", a => a.status]
], attendance));
$("reportCoursesButton").addEventListener("click", () => csvDownload("campusflow-courses.csv", [
  ["Course code", c => c.courseCode], ["Course name", c => c.courseName], ["Credits", c => c.credits],
  ["Capacity", c => c.capacity], ["Prerequisite", c => c.prerequisiteCode || "None"],
  ["Confirmed", c => c.confirmed], ["Waitlisted", c => c.waitlisted]
], courses));

$("helpButton").addEventListener("click", async () => {
  try {
    const [s, c, r, a] = await Promise.all([api("/students"), api("/courses"), api("/enrollments"), api("/attendance")]);
    alert(`CampusFlow API status: Connected\nLogged in as: ${currentUser?.username}\nStudents: ${s.length}\nCourses: ${c.length}\nEnrollments: ${r.length}\nAttendance entries: ${a.length}`);
  } catch (error) {
    alert(`Backend connection failed.\n${error.message}\nStart WebServer.java and check MySQL.`);
  }
});

/* STUDENT ACCOUNTS (PHASE 6) */

async function loadAccounts() {
  try {
    const list = await api("/admin/accounts");
    if (Array.isArray(list)) {
      accounts = list;
      renderAccounts();
    }
  } catch (error) {
    console.warn("Could not reload accounts:", error.message);
    toast("Could not reload accounts: " + error.message);
  }
}

function renderAccounts() {
  if (!$("accountsBody")) return;
  const search = $("accountsSearch") ? $("accountsSearch").value.trim().toLowerCase() : "";
  const filter = $("accountsStatusFilter") ? $("accountsStatusFilter").value : "all";

  const total = accounts.length;
  const linked = accounts.filter(a => a.role === "STUDENT" && a.studentId != null).length;
  const unlinked = accounts.filter(a => a.role === "STUDENT" && a.studentId == null).length;

  if ($("totalAccountsCount")) $("totalAccountsCount").textContent = total;
  if ($("linkedAccountsCount")) $("linkedAccountsCount").textContent = linked;
  if ($("unlinkedAccountsCount")) $("unlinkedAccountsCount").textContent = unlinked;
  if ($("navAccountsCount")) $("navAccountsCount").textContent = unlinked;

  const filtered = accounts.filter(acct => {
    if (filter === "linked" && (acct.role !== "STUDENT" || acct.studentId == null)) return false;
    if (filter === "unlinked" && (acct.role !== "STUDENT" || acct.studentId != null)) return false;
    if (filter === "admin" && acct.role !== "ADMIN") return false;

    if (!search) return true;
    return (acct.fullName || "").toLowerCase().includes(search)
      || (acct.username || "").toLowerCase().includes(search)
      || (acct.email || "").toLowerCase().includes(search)
      || (acct.studentName || "").toLowerCase().includes(search)
      || String(acct.studentId || "").includes(search)
      || String(acct.userId || "").includes(search);
  });

  $("accountsBody").innerHTML = filtered.map(acct => {
    const isStudent = acct.role === "STUDENT";
    const isLinked = isStudent && acct.studentId != null;

    let linkedDisplay = `<span class="muted">— (System admin)</span>`;
    let statusPillHtml = `<span class="status-pill" style="background:#ede8ff;color:#5d48e8;">Admin</span>`;
    let actionsHtml = `<span class="muted" style="font-size:11px;">Protected</span>`;

    if (isStudent) {
      if (isLinked) {
        linkedDisplay = `<strong>${esc(acct.studentName || "Student")}</strong> <span class="muted">(ID ${esc(acct.studentId)})</span>`;
        statusPillHtml = `<span class="status-pill status-active">Linked</span>`;
        actionsHtml = `
          <button type="button" class="small-action" data-change-user="${esc(acct.userId)}" data-student-id="${esc(acct.studentId)}">Change</button>
          <button type="button" class="small-action danger-action" data-unlink-user="${esc(acct.userId)}" data-username="${esc(acct.username)}" data-student-id="${esc(acct.studentId)}">Unlink</button>
        `;
      } else {
        linkedDisplay = `<span class="muted" style="color:#d34d7b;font-weight:600;">Not linked</span>`;
        statusPillHtml = `<span class="status-pill status-pending">Unlinked</span>`;
        actionsHtml = `
          <button type="button" class="small-action" data-link-user="${esc(acct.userId)}">Link student</button>
        `;
      }
    }

    const roleBadge = acct.role === "ADMIN"
      ? `<span class="role-badge admin-badge">ADMIN</span>`
      : `<span class="role-badge student-badge">STUDENT</span>`;

    return `<tr>
      <td>
        <div class="student-cell">
          <div class="student-avatar">${esc(initials(acct.fullName || acct.username))}</div>
          <div class="student-name">
            <strong>${esc(acct.fullName || acct.username)}</strong>
            <span>${esc(acct.email)} · @${esc(acct.username)}</span>
          </div>
        </div>
      </td>
      <td><strong>#${esc(acct.userId)}</strong></td>
      <td>${roleBadge}</td>
      <td>${linkedDisplay}</td>
      <td>${statusPillHtml}</td>
      <td class="action-cell" style="white-space:nowrap">${actionsHtml}</td>
    </tr>`;
  }).join("");

  $("accountsEmpty").classList.toggle("hidden", filtered.length > 0);

  // Row button event listeners
  $("accountsBody").querySelectorAll("[data-link-user]").forEach(btn =>
    btn.addEventListener("click", () => openLinkModal(Number(btn.dataset.linkUser))));

  $("accountsBody").querySelectorAll("[data-change-user]").forEach(btn =>
    btn.addEventListener("click", () => openLinkModal(Number(btn.dataset.changeUser), Number(btn.dataset.studentId))));

  $("accountsBody").querySelectorAll("[data-unlink-user]").forEach(btn =>
    btn.addEventListener("click", async () => {
      const uid = Number(btn.dataset.unlinkUser);
      const uname = btn.dataset.username;
      const sid = btn.dataset.studentId;
      if (!confirm(`Are you sure you want to unlink student record ID ${sid} from user account '${uname}'?\n\nThe student will immediately lose access to personal academic records.`)) return;
      try {
        const res = await api("/admin/accounts/unlink", { userId: uid });
        toast(res.message || "Account unlinked successfully.");
        await loadAccounts();
      } catch (err) {
        toast("Failed to unlink account: " + err.message);
      }
    }));
}

function openLinkModal(userId = null, currentStudentId = null) {
  $("linkAccountForm").reset();
  setMessage("linkAccountError", "");

  const studentUsers = accounts.filter(a => a.role === "STUDENT");
  $("linkTargetUser").innerHTML = `<option value="">Select student user account</option>` +
    studentUsers.map(u => {
      const statusText = u.studentId ? `(Linked: ID ${u.studentId})` : `(Unlinked)`;
      return `<option value="${u.userId}">${esc(u.fullName || u.username)} (@${esc(u.username)}) ${statusText}</option>`;
    }).join("");

  if (userId) {
    $("linkTargetUser").value = String(userId);
  }

  const linkedStudentIds = new Set(
    accounts.filter(a => a.studentId != null && Number(a.userId) !== Number(userId)).map(a => Number(a.studentId))
  );

  $("linkTargetStudent").innerHTML = `<option value="">Select student record</option>` +
    students.map(s => {
      const isTaken = linkedStudentIds.has(Number(s.studentId));
      const takenNote = isTaken ? " (Already linked to another user)" : "";
      return `<option value="${s.studentId}" ${isTaken ? "disabled" : ""}>ID ${s.studentId} · ${esc(s.studentName)} (${esc(s.email)})${takenNote}</option>`;
    }).join("");

  if (currentStudentId) {
    $("linkTargetStudent").value = String(currentStudentId);
    $("linkAccountModalTitle").textContent = "Change student link";
    $("saveLinkButton").textContent = "Update link";
  } else {
    $("linkAccountModalTitle").textContent = "Link student account";
    $("saveLinkButton").textContent = "Approve link";
  }

  openModal("linkAccountModal");
}

$("openLinkModalButton").addEventListener("click", () => openLinkModal());
$("closeLinkAccountModal").addEventListener("click", () => closeModal("linkAccountModal"));
$("cancelLinkAccountModal").addEventListener("click", () => closeModal("linkAccountModal"));
$("refreshAccountsButton").addEventListener("click", loadAccounts);
$("accountsSearch").addEventListener("input", renderAccounts);
$("accountsStatusFilter").addEventListener("change", renderAccounts);

$("linkAccountForm").addEventListener("submit", async event => {
  event.preventDefault();
  const userId = $("linkTargetUser").value;
  const studentId = $("linkTargetStudent").value;
  const verified = $("linkConfirmVerified").checked;

  if (!userId || !studentId) {
    setMessage("linkAccountError", "Please select both a user account and a student record.", true);
    return;
  }
  if (!verified) {
    setMessage("linkAccountError", "Administrator must confirm student identity verification.", true);
    return;
  }

  try {
    const res = await api("/admin/accounts/link", { userId, studentId });
    closeModal("linkAccountModal");
    toast(res.message || "Account successfully linked.");
    await loadAccounts();
  } catch (error) {
    setMessage("linkAccountError", error.message, true);
  }
});

/* CLOSE MODALS */

const MODALS = ["studentModal", "enrollmentModal", "importModal", "departmentModal", "assignModal", "linkAccountModal"];
MODALS.forEach(id =>
  $(id).addEventListener("click", event => { if (event.target === $(id)) closeModal(id); }));
document.addEventListener("keydown", event => {
  if (event.key === "Escape") MODALS.forEach(closeModal);
});

/* START: check login, then load data */
loadUser().then(ok => { if (ok) loadData(); });
