"use strict";

// CampusFlow Dedicated Student Portal Login & Registration Controller
const $ = id => document.getElementById(id);
let isSignupMode = false;

function showMessage(id, text, success = false, isAdminAttempt = false) {
  const node = $(id);
  if (!text) {
    node.innerHTML = "";
    node.classList.add("hidden");
    return;
  }

  const icon = success 
    ? `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0;margin-top:2px;"><polyline points="20 6 9 17 4 12"/></svg>`
    : `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0;margin-top:2px;"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>`;

  if (isAdminAttempt) {
    node.innerHTML = `${icon}<div>`
      + `<div style="font-weight:700;">Administrator Account Detected</div>`
      + `<div style="margin-top:3px;font-size:12px;">${text}</div>`
      + `<div style="margin-top:8px;"><a href="/admin-login.html" style="color:#0284c7;font-weight:700;text-decoration:underline;">Click here to sign in via the Admin Console &rarr;</a></div>`
      + `</div>`;
  } else {
    node.innerHTML = `${icon}<div>${text}</div>`;
  }

  node.classList.toggle("success", success);
  node.classList.toggle("error", !success);
  node.classList.remove("hidden");
}

function setBusy(button, busy, label) {
  button.disabled = busy;
  const span = button.querySelector("span");
  if (span) span.textContent = label;
}

// Toggle between Sign In and Sign Up tabs
function setMode(signup) {
  isSignupMode = signup;
  
  // Segmented tab active styles
  $("tabSignIn").classList.toggle("active", !signup);
  $("tabRegister").classList.toggle("active", signup);

  // Form visibility
  $("studentLoginForm").classList.toggle("hidden", signup);
  $("studentSignupForm").classList.toggle("hidden", !signup);

  // Headers
  $("studentKicker").textContent = signup ? "New Student Registration" : "Student Access";
  $("studentTitle").textContent = signup ? "Create Account" : "Student Portal";
  $("studentSubtitle").textContent = signup
    ? "Create your student login account. Your academic record will be linked by an administrator."
    : "Sign in to view your courses, verified attendance, published grades, and calculated GPA.";

  showMessage("studentLoginMessage", "");
  showMessage("studentSignupMessage", "");
  (signup ? $("studentSignupFullName") : $("studentLoginUsername")).focus();
}

$("tabSignIn").addEventListener("click", () => setMode(false));
$("tabRegister").addEventListener("click", () => setMode(true));

// Password visibility toggles
document.querySelectorAll("[data-toggle-password]").forEach(button => {
  button.addEventListener("click", () => {
    const input = $(button.dataset.togglePassword);
    if (!input) return;
    const show = input.type === "password";
    input.type = show ? "text" : "password";
    button.classList.toggle("showing", show);
    button.setAttribute("aria-pressed", String(show));
    button.setAttribute("aria-label", show ? "Hide password" : "Show password");
    input.focus();
  });
});

// Caps Lock detection on login password
(function setupCapsLock() {
  const check = event => {
    if (event.getModifierState) $("studentLoginCaps").classList.toggle("hidden", !event.getModifierState("CapsLock"));
  };
  const pw = $("studentLoginPassword");
  pw.addEventListener("keydown", check);
  pw.addEventListener("keyup", check);
  pw.addEventListener("blur", () => $("studentLoginCaps").classList.add("hidden"));
})();

// Password strength calculation
function passwordScore(password) {
  if (!password) return 0;
  let score = 0;
  if (password.length >= 8) score++;
  if (/[a-z]/.test(password) && /[A-Z]/.test(password)) score++;
  if (/\d/.test(password)) score++;
  if (/[^A-Za-z0-9]/.test(password)) score++;
  return password.length < 8 ? Math.min(score, 1) : score;
}

function updateStrength() {
  const password = $("studentSignupPassword").value;
  const score = passwordScore(password);
  const labels = [
    "Min 8 characters with upper, lower, numbers & symbols.",
    "Weak password",
    "Fair password",
    "Good password",
    "Strong password"
  ];
  $("studentStrengthMeter").dataset.score = score;
  $("studentStrengthText").textContent = password ? `${labels[score]}${password.length < 8 ? " — needs at least 8 characters" : ""}` : labels[0];
  $("studentStrengthText").className = "field-hint" + (password ? (score >= 3 ? " ok" : score <= 1 ? " error" : "") : "");
  updateMatch();
}

function updateMatch() {
  const confirm = $("studentSignupConfirm").value;
  const node = $("studentMatchText");
  if (!confirm) {
    node.textContent = "";
    node.className = "field-hint";
    return;
  }
  const same = confirm === $("studentSignupPassword").value;
  node.textContent = same ? "✓ Passwords match" : "✗ Passwords do not match";
  node.className = "field-hint " + (same ? "ok" : "error");
}

$("studentSignupPassword").addEventListener("input", updateStrength);
$("studentSignupConfirm").addEventListener("input", updateMatch);

// Student login form submit with portal=STUDENT
$("studentLoginForm").addEventListener("submit", async event => {
  event.preventDefault();
  const username = $("studentLoginUsername").value.trim();
  const password = $("studentLoginPassword").value;
  if (!username || !password) {
    showMessage("studentLoginMessage", "Please enter your username/email and password.");
    return;
  }

  setBusy($("studentLoginSubmit"), true, "Signing in...");
  try {
    const response = await fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ username, password, portal: "STUDENT" })
    });

    let body = {};
    try { body = await response.json(); } catch {}

    if (response.status === 403) {
      // Forbidden: administrator account attempted to sign in on student portal
      showMessage("studentLoginMessage", body.message || "This portal is strictly reserved for Students. Administrators must use the Admin Console.", false, true);
      setBusy($("studentLoginSubmit"), false, "Sign in to Student Portal");
      return;
    }

    if (!response.ok) {
      throw new Error(body.message || `Sign in failed (${response.status})`);
    }

    showMessage("studentLoginMessage", "Signed in. Opening your student portal...", true);
    setTimeout(() => {
      window.location.href = "/student.html";
    }, 400);
  } catch (error) {
    showMessage("studentLoginMessage", error.message || "Authentication failed.");
    setBusy($("studentLoginSubmit"), false, "Sign in to Student Portal");
  }
});

// Student sign-up form submit
$("studentSignupForm").addEventListener("submit", async event => {
  event.preventDefault();
  const fullName = $("studentSignupFullName").value.trim();
  const username = $("studentSignupUsername").value.trim();
  const email = $("studentSignupEmail").value.trim();
  const password = $("studentSignupPassword").value;
  const confirm = $("studentSignupConfirm").value;

  if (!fullName || !username || !email || !password) return showMessage("studentSignupMessage", "Please fill in every field.");
  if (!/^[A-Za-z0-9_.-]{3,50}$/.test(username)) return showMessage("studentSignupMessage", "Username must be 3–50 letters, numbers, dot, underscore or hyphen.");
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) return showMessage("studentSignupMessage", "Enter a valid email address.");
  if (password.length < 8) return showMessage("studentSignupMessage", "Password must be at least 8 characters.");
  if (password !== confirm) return showMessage("studentSignupMessage", "Passwords do not match.");

  setBusy($("studentSignupSubmit"), true, "Creating account...");
  try {
    const response = await fetch("/api/auth/signup", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ fullName, username, email, password })
    });
    let result = {};
    try { result = await response.json(); } catch {}
    if (!response.ok) throw new Error(result.message || `Registration failed (${response.status})`);

    $("studentSignupForm").reset();
    updateStrength();
    setMode(false);
    $("studentLoginUsername").value = username;
    $("studentLoginPassword").focus();
    showMessage("studentLoginMessage", result.message || "Student account created! Please sign in.", true);
  } catch (error) {
    showMessage("studentSignupMessage", error.message);
  } finally {
    setBusy($("studentSignupSubmit"), false, "Complete Registration");
  }
});

// Check if user already has an active session and redirect accordingly
(async function checkExistingSession() {
  try {
    const response = await fetch("/api/auth/me");
    if (response.ok) {
      const user = await response.json();
      if (user?.role === "STUDENT") {
        window.location.href = "/student.html";
      } else if (user?.role === "ADMIN") {
        window.location.href = "/index.html";
      }
    }
  } catch {}
})();
