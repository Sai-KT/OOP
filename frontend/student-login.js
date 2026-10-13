"use strict";

// CampusFlow Dedicated Student Portal Login Controller
const $ = id => document.getElementById(id);
let signupMode = false;

function showMessage(id, text, success = false, isAdminAttempt = false) {
  const node = $(id);
  if (!text) {
    node.innerHTML = "";
    node.classList.add("hidden");
    return;
  }
  if (isAdminAttempt) {
    node.innerHTML = `<div><strong>Administrator Account Detected</strong></div>`
      + `<div style="margin-top:4px;">${text}</div>`
      + `<div style="margin-top:8px;"><a href="/admin-login.html" style="color:#0070f3;font-weight:750;text-decoration:underline;">Click here to sign in via the Admin Console →</a></div>`;
  } else {
    node.textContent = text;
  }
  node.classList.toggle("success", success);
  node.classList.remove("hidden");
}

function setBusy(button, busy, label) {
  button.disabled = busy;
  button.querySelector("span").textContent = label;
}

// Toggle between Sign In and Sign Up
function setMode(signup) {
  signupMode = signup;
  $("studentLoginForm").classList.toggle("hidden", signup);
  $("studentSignupForm").classList.toggle("hidden", !signup);
  $("studentAuthKicker").textContent = signup ? "NEW STUDENT REGISTRATION" : "STUDENT SIGN IN";
  $("studentAuthTitle").textContent = signup ? "Register Student Account" : "Student Portal";
  $("studentAuthSubtitle").textContent = signup
    ? "Create your student login account. Your campus student record will be linked by an administrator."
    : "Enter your student account details to access your academic dashboard.";
  $("studentAuthSwitchText").firstChild.textContent = signup ? "Already have an account? " : "New student? ";
  $("switchStudentAuth").textContent = signup ? "Sign in" : "Create an account";
  showMessage("studentLoginMessage", "");
  showMessage("studentSignupMessage", "");
  (signup ? $("studentSignupFullName") : $("studentLoginUsername")).focus();
}

$("switchStudentAuth").addEventListener("click", () => setMode(!signupMode));

// Password toggles (eye icons)
document.querySelectorAll("[data-toggle-password]").forEach(button => {
  button.addEventListener("click", () => {
    const input = $(button.dataset.togglePassword);
    const show = input.type === "password";
    input.type = show ? "text" : "password";
    button.classList.toggle("showing", show);
    button.setAttribute("aria-pressed", String(show));
    button.setAttribute("aria-label", show ? "Hide password" : "Show password");
    input.focus();
  });
});

// Caps Lock watcher
(function setupCapsLock() {
  const check = event => {
    if (event.getModifierState) $("studentLoginCaps").classList.toggle("hidden", !event.getModifierState("CapsLock"));
  };
  const pw = $("studentLoginPassword");
  pw.addEventListener("keydown", check);
  pw.addEventListener("keyup", check);
  pw.addEventListener("blur", () => $("studentLoginCaps").classList.add("hidden"));
})();

// Password strength meter
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
  const labels = ["Use at least 8 characters — mix upper & lower case, numbers and symbols.",
    "Weak password", "Fair password", "Good password", "Strong password"];
  $("studentStrengthMeter").dataset.score = score;
  $("studentStrengthText").textContent = password ? `${labels[score]}${password.length < 8 ? " — needs at least 8 characters" : ""}` : labels[0];
  $("studentStrengthText").className = "auth-field-hint" + (password ? (score >= 3 ? " hint-ok" : score <= 1 ? " hint-bad" : "") : "");
  updateMatch();
}

function updateMatch() {
  const confirm = $("studentSignupConfirm").value;
  const node = $("studentMatchText");
  if (!confirm) { node.textContent = ""; node.className = "auth-field-hint"; return; }
  const same = confirm === $("studentSignupPassword").value;
  node.textContent = same ? "✓ Passwords match" : "✗ Passwords do not match";
  node.className = "auth-field-hint " + (same ? "hint-ok" : "hint-bad");
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

  setBusy($("studentSignupSubmit"), true, "Registering student account...");
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
    setBusy($("studentSignupSubmit"), false, "Create Student Account");
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

if (window.location.hash === "#signup") setMode(true);
