"use strict";

// CampusFlow Student Portal Frontend Controller (Phase 7)
// Pure Vanilla JavaScript communicating with read-only /api/student/* endpoints.

const API = "/api";

let currentUser = null;
let profile = null;
let courses = [];
let attendance = [];
let grades = [];
let gpa = 0.0;
let toastTimer = null;

const $ = id => document.getElementById(id);

const esc = value => String(value ?? "").replace(/[&<>"']/g, char => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
}[char]));

const initials = name => String(name || "S").trim()
  .split(/\s+/).slice(0, 2).map(word => word[0] || "").join("").toUpperCase();

function toast(message) {
  const node = $("toast");
  if (!node) return;
  node.textContent = message;
  node.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => node.classList.remove("show"), 3500);
}

function statusPill(status) {
  const s = String(status || "").toUpperCase();
  if (s === "CONFIRMED" || s === "PRESENT" || s === "PASSED") {
    return `<span class="status-pill status-active">${esc(status)}</span>`;
  }
  if (s === "WAITLISTED" || s === "LATE") {
    return `<span class="status-pill status-pending">${esc(status)}</span>`;
  }
  if (s === "ABSENT" || s === "FAILED") {
    return `<span class="status-pill status-inactive">${esc(status)}</span>`;
  }
  return `<span class="status-pill">${esc(status)}</span>`;
}

/* API HELPER */
async function api(path, data) {
  const options = data
    ? { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams(data) }
    : {};
  const response = await fetch(`${API}${path}`, options);
  let body = null;
  try { body = await response.json(); } catch {}
  if (response.status === 401) {
    window.location.href = "/login.html";
    throw new Error("Session expired. Please log in again.");
  }
  if (!response.ok) {
    const error = new Error(body?.message || `Request failed (${response.status})`);
    error.status = response.status;
    throw error;
  }
  return body;
}

/* AUTHENTICATION & ROLE VERIFICATION */
async function loadUser() {
  try {
    currentUser = await api("/auth/me");
  } catch (error) {
    window.location.href = "/login.html";
    return false;
  }

  if (!currentUser) {
    window.location.href = "/login.html";
    return false;
  }

  // Role routing guard: redirect administrators to the admin dashboard
  if (currentUser.role === "ADMIN") {
    window.location.href = "/index.html";
    return false;
  }

  // Update topbar identity
  const name = currentUser.fullName || currentUser.username || "Student";
  $("userName").textContent = name;
  $("userAvatar").textContent = initials(name);
  if ($("userRoleBadge")) {
    $("userRoleBadge").textContent = currentUser.role || "STUDENT";
  }

  // Check if student account is linked to a student record
  if (currentUser.studentId == null) {
    showUnlinkedState();
    return false;
  }

  return true;
}

function showUnlinkedState() {
  // Hide all regular pages and navigation items
  document.querySelectorAll(".page").forEach(p => p.classList.add("hidden"));
  $("unlinkedPage").classList.remove("hidden");
  $("breadcrumb").textContent = "Account Pending Verification";
  $("unlinkedUserName").textContent = currentUser.fullName || currentUser.username;

  // Disable sidebar navigation items to prevent confusion
  document.querySelectorAll(".navigation .nav-item").forEach(button => {
    button.disabled = true;
    button.classList.remove("active");
  });
}

function restoreLinkedState() {
  $("unlinkedPage").classList.add("hidden");
  document.querySelectorAll(".navigation .nav-item").forEach(button => {
    button.disabled = false;
  });
  showPage("overview");
}

/* NAVIGATION */
const PAGES = ["overview", "profile", "courses", "attendance", "grades"];
const PAGE_TITLES = {
  overview: "Overview",
  profile: "My Profile",
  courses: "My Courses",
  attendance: "My Attendance",
  grades: "My Grades & GPA"
};

function showPage(page) {
  if (currentUser?.studentId == null) {
    showUnlinkedState();
    return;
  }
  PAGES.forEach(name => {
    const el = $(`${name}Page`);
    if (el) el.classList.toggle("hidden", name !== page);
  });
  document.querySelectorAll(".nav-item").forEach(button => {
    button.classList.toggle("active", button.dataset.page === page);
  });
  $("breadcrumb").textContent = PAGE_TITLES[page] || (page[0].toUpperCase() + page.slice(1));
  $("sidebar").classList.remove("open");
}

/* DATA FETCHING */
async function loadStudentData() {
  try {
    const [p, c, a, g, gpaVal] = await Promise.all([
      api("/student/me"),
      api("/student/courses"),
      api("/student/attendance"),
      api("/student/grades"),
      api("/student/gpa")
    ]);

    profile = p || {};
    courses = Array.isArray(c) ? c : [];
    attendance = Array.isArray(a) ? a : [];
    grades = Array.isArray(g) ? g : [];
    gpa = typeof gpaVal === "number" ? gpaVal : (typeof gpaVal?.gpa === "number" ? gpaVal.gpa : (profile.gpa || 0.0));

    restoreLinkedState();
    renderAll();
  } catch (error) {
    console.error("Failed to load student data:", error);
    if (error.status === 403 && (error.message.includes("not linked") || error.message.includes("student record"))) {
      currentUser.studentId = null;
      showUnlinkedState();
    } else {
      toast("Error loading student records: " + error.message);
    }
  }
}

/* RENDER CONTROLLER */
function renderAll() {
  renderOverview();
  renderProfile();
  renderCourses();
  renderAttendance();
  renderGrades();
  updateNavCounters();
}

function updateNavCounters() {
  $("navCoursesCount").textContent = courses.length;
  const attPct = computeAttendanceRate();
  $("navAttendanceRate").textContent = attPct === null ? "—" : `${attPct}%`;
  $("navGpaValue").textContent = Number(gpa).toFixed(2);
}

function computeAttendanceRate() {
  if (!attendance.length) return null;
  const presentOrLate = attendance.filter(r => {
    const s = String(r.status || "").toUpperCase();
    return s === "PRESENT" || s === "LATE";
  }).length;
  return Math.round((presentOrLate / attendance.length) * 100);
}

/* 1. OVERVIEW PAGE */
function renderOverview() {
  const firstName = (profile.studentName || currentUser.fullName || "Student").split(" ")[0];
  const hour = new Date().getHours();
  const timeGreeting = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
  $("greeting").textContent = `${timeGreeting}, ${firstName} ✦`;

  $("overviewSubtitle").innerHTML = `Student ID: <strong>${esc(profile.studentId || "—")}</strong> • ` +
    `Department: <strong>${esc(profile.departmentName || "General")}</strong> • ` +
    `Semester <strong>${esc(profile.semester || 1)}</strong>`;

  $("overviewCoursesCount").textContent = courses.length;

  const attRate = computeAttendanceRate();
  $("overviewAttendance").textContent = attRate === null ? "—" : `${attRate}%`;
  $("overviewAttendanceRatio").textContent = attendance.length ? `${attendance.length} total sessions recorded` : "No sessions marked yet";
  $("overviewAttendancePercent").textContent = attRate === null ? "No records" : `${attRate}%`;
  $("overviewAttendanceBar").style.width = attRate === null ? "0%" : `${Math.min(100, Math.max(0, attRate))}%`;

  $("overviewGpa").textContent = Number(gpa).toFixed(2);
  $("overviewStudentType").textContent = profile.studentType ? profile.studentType.toLowerCase() : "Undergraduate";
  $("overviewDepartmentShort").textContent = profile.departmentName || "CampusFlow Academic";

  $("overviewGuidanceText").textContent = profile.guidance || "Maintain active course participation and track prerequisite requirements.";

  // Quick view courses table
  const tbody = $("overviewCoursesBody");
  tbody.innerHTML = "";
  if (!courses.length) {
    $("overviewCoursesEmpty").classList.remove("hidden");
  } else {
    $("overviewCoursesEmpty").classList.add("hidden");
    courses.slice(0, 5).forEach(c => {
      const tr = document.createElement("tr");
      tr.innerHTML = `
        <td><strong>${esc(c.courseName)}</strong></td>
        <td><code>${esc(c.courseCode)}</code></td>
        <td>${esc(c.credits)} cr</td>
        <td>${statusPill(c.status || "CONFIRMED")}</td>
      `;
      tbody.appendChild(tr);
    });
  }
}

/* 2. MY PROFILE PAGE */
function renderProfile() {
  $("profileStudentId").textContent = profile.studentId || "—";
  $("profileName").textContent = profile.studentName || currentUser.fullName || "—";
  $("profileEmail").textContent = profile.email || currentUser.email || "—";
  $("profileDepartment").textContent = profile.departmentName ? `${profile.departmentName} (ID: ${profile.departmentId})` : "Not assigned";
  $("profileSemester").textContent = profile.semester ? `Semester ${profile.semester}` : "—";
  $("profileType").textContent = profile.studentType || "UNDERGRADUATE";
  $("profileGpa").textContent = Number(gpa).toFixed(2);
  $("profileGuidanceText").textContent = profile.guidance || "Academic standing is regular. Consult your department advisor for credit requirements.";
}

/* 3. MY COURSES PAGE */
function renderCourses() {
  const query = ($("coursesSearch")?.value || "").toLowerCase().trim();
  const filtered = courses.filter(c =>
    (c.courseCode || "").toLowerCase().includes(query) ||
    (c.courseName || "").toLowerCase().includes(query)
  );

  const tbody = $("coursesTableBody");
  tbody.innerHTML = "";

  if (!filtered.length) {
    $("coursesEmpty").classList.remove("hidden");
    return;
  }
  $("coursesEmpty").classList.add("hidden");

  filtered.forEach(c => {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td><strong><code>${esc(c.courseCode)}</code></strong></td>
      <td>${esc(c.courseName)}</td>
      <td>${esc(c.credits)} Credits</td>
      <td>${c.prerequisiteCode ? `<code>${esc(c.prerequisiteCode)}</code>` : '<span class="muted">None</span>'}</td>
      <td>${c.capacity ? `${esc(c.capacity)} seats` : '<span class="muted">—</span>'}</td>
      <td>${statusPill(c.status || "CONFIRMED")}</td>
    `;
    tbody.appendChild(tr);
  });
}

/* 4. MY ATTENDANCE PAGE */
function renderAttendance() {
  const presentCount = attendance.filter(r => String(r.status || "").toUpperCase() === "PRESENT").length;
  const lateCount = attendance.filter(r => String(r.status || "").toUpperCase() === "LATE").length;
  const absentCount = attendance.filter(r => String(r.status || "").toUpperCase() === "ABSENT").length;
  const total = attendance.length;

  $("attPresentCount").textContent = presentCount;
  $("attLateCount").textContent = lateCount;
  $("attAbsentCount").textContent = absentCount;

  const rate = total > 0 ? Math.round(((presentCount + lateCount) / total) * 100) : null;
  $("attRateStat").textContent = rate === null ? "—" : `${rate}%`;

  const query = ($("attendanceSearch")?.value || "").toLowerCase().trim();
  const statusFilter = $("attendanceStatusFilter")?.value || "all";

  const filtered = attendance.filter(r => {
    const matchQuery = (r.courseCode || "").toLowerCase().includes(query) ||
      (r.courseName || "").toLowerCase().includes(query);
    const matchStatus = statusFilter === "all" || String(r.status || "").toUpperCase() === statusFilter;
    return matchQuery && matchStatus;
  });

  const tbody = $("attendanceTableBody");
  tbody.innerHTML = "";

  if (!filtered.length) {
    $("attendanceEmpty").classList.remove("hidden");
    return;
  }
  $("attendanceEmpty").classList.add("hidden");

  filtered.forEach(r => {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td>${esc(r.date || "—")}</td>
      <td><code>${esc(r.courseCode || "—")}</code></td>
      <td><strong>${esc(r.courseName || "—")}</strong></td>
      <td>${statusPill(r.status || "PRESENT")}</td>
    `;
    tbody.appendChild(tr);
  });
}

/* 5. MY GRADES PAGE */
function renderGrades() {
  $("gradesGpaBig").textContent = Number(gpa).toFixed(2);
  $("gradesSubjectCount").textContent = grades.length;

  const allPassed = grades.length > 0 && grades.every(g => g.passed);
  $("gradesPassStatus").innerHTML = grades.length === 0
    ? '<span class="muted">No grades</span>'
    : allPassed
      ? '<span class="status-pill status-active">Passed (All Subjects)</span>'
      : '<span class="status-pill status-inactive">Attention Required</span>';

  const tbody = $("gradesTableBody");
  tbody.innerHTML = "";

  if (!grades.length) {
    $("gradesEmpty").classList.remove("hidden");
    return;
  }
  $("gradesEmpty").classList.add("hidden");

  grades.forEach(g => {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td><code>#${esc(g.departmentId)}</code></td>
      <td><strong>${esc(g.departmentName)}</strong></td>
      <td>${typeof g.marks === "number" ? esc(g.marks.toFixed(1)) : esc(g.marks)} / 100</td>
      <td>${g.passed ? '<span class="status-pill status-active">Passed</span>' : '<span class="status-pill status-inactive">Failed (&lt; 40)</span>'}</td>
    `;
    tbody.appendChild(tr);
  });
}

/* EVENT LISTENERS SETUP */
function setupEvents() {
  // Navigation tabs
  document.querySelectorAll("[data-page]").forEach(btn => {
    btn.addEventListener("click", e => {
      e.preventDefault();
      showPage(btn.dataset.page);
    });
  });

  document.querySelectorAll("[data-goto]").forEach(btn => {
    btn.addEventListener("click", () => showPage(btn.dataset.goto));
  });

  // Mobile menu
  $("menuButton")?.addEventListener("click", () => {
    $("sidebar").classList.toggle("open");
  });

  // Logout actions
  const doLogout = async () => {
    try { await api("/auth/logout", {}); } catch {}
    window.location.href = "/login.html";
  };

  $("logoutButton")?.addEventListener("click", doLogout);
  $("unlinkedLogoutBtn")?.addEventListener("click", doLogout);

  // Unlinked refresh status check
  $("unlinkedRefreshBtn")?.addEventListener("click", async () => {
    toast("Checking account linkage status...");
    const ok = await loadUser();
    if (ok) {
      toast("Account linked! Loading your portal...");
      await loadStudentData();
    } else {
      toast("Account is still pending administrator linking.");
    }
  });

  // Filters & search
  $("coursesSearch")?.addEventListener("input", renderCourses);
  $("attendanceSearch")?.addEventListener("input", renderAttendance);
  $("attendanceStatusFilter")?.addEventListener("change", renderAttendance);

  // Academic support modal
  const openSupport = () => $("supportModal")?.classList.remove("hidden");
  const closeSupport = () => $("supportModal")?.classList.add("hidden");
  $("supportBtn")?.addEventListener("click", openSupport);
  $("closeSupportBtn")?.addEventListener("click", closeSupport);
  $("closeSupportBtn2")?.addEventListener("click", closeSupport);
  $("supportModal")?.addEventListener("click", e => {
    if (e.target === $("supportModal")) closeSupport();
  });
}

/* INITIALIZATION */
document.addEventListener("DOMContentLoaded", async () => {
  setupEvents();
  const isStudent = await loadUser();
  if (isStudent) {
    await loadStudentData();
  }
});
