"use strict";

// Login and sign-up page. Talks to WebServer.java at /api/auth/...
const $ = id => document.getElementById(id);
let signupMode = false;

async function post(path, data) {
  const response = await fetch(`/api/auth/${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(data)
  });
  let body = {};
  try { body = await response.json(); } catch {}
  if (!response.ok) throw new Error(body.message || `Request failed (${response.status})`);
  return body;
}

function showMessage(id, text, success = false) {
  const node = $(id);
  node.textContent = text;
  node.classList.toggle("success", success);
  node.classList.toggle("hidden", !text);
}

function setBusy(button, busy, label) {
  button.disabled = busy;
  button.querySelector("span").textContent = label;
}

/* SWITCH BETWEEN SIGN IN AND SIGN UP */
function setMode(signup) {
  signupMode = signup;
  $("loginForm").classList.toggle("hidden", signup);
  $("signupForm").classList.toggle("hidden", !signup);
  $("authKicker").textContent = signup ? "NEW ACCOUNT" : "WELCOME BACK";
  $("authTitle").textContent = signup ? "Create your account" : "Sign in to your workspace";
  $("authSubtitle").textContent = signup
    ? "Fill in your details to start using CampusFlow."
    : "Enter your details below to access your dashboard.";
  $("authSwitchText").firstChild.textContent = signup ? "Already have an account? " : "New to CampusFlow? ";
  $("switchAuth").textContent = signup ? "Sign in" : "Create an account";
  showMessage("loginMessage", "");
  showMessage("signupMessage", "");
  (signup ? $("signupFullName") : $("loginUsername")).focus();
}

$("switchAuth").addEventListener("click", () => setMode(!signupMode));

/* SHOW / HIDE PASSWORD (eye button) */
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

/* CAPS LOCK WARNING */
function capsWatcher(inputId, warningId) {
  const check = event => {
    if (event.getModifierState) $(warningId).classList.toggle("hidden", !event.getModifierState("CapsLock"));
  };
  $(inputId).addEventListener("keydown", check);
  $(inputId).addEventListener("keyup", check);
  $(inputId).addEventListener("blur", () => $(warningId).classList.add("hidden"));
}
capsWatcher("loginPassword", "loginCaps");
capsWatcher("signupPassword", "signupCaps");

/* PASSWORD STRENGTH + MATCH CHECK */
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
  const password = $("signupPassword").value;
  const score = passwordScore(password);
  const labels = ["Use at least 8 characters — mix upper & lower case, numbers and symbols.",
    "Weak password", "Fair password", "Good password", "Strong password"];
  $("strengthMeter").dataset.score = score;
  $("strengthText").textContent = password ? `${labels[score]}${password.length < 8 ? " — needs at least 8 characters" : ""}` : labels[0];
  $("strengthText").className = "auth-field-hint" + (password ? (score >= 3 ? " hint-ok" : score <= 1 ? " hint-bad" : "") : "");
  updateMatch();
}

function updateMatch() {
  const confirm = $("signupConfirmPassword").value;
  const node = $("matchText");
  if (!confirm) { node.textContent = ""; node.className = "auth-field-hint"; return; }
  const same = confirm === $("signupPassword").value;
  node.textContent = same ? "✓ Passwords match" : "✗ Passwords do not match";
  node.className = "auth-field-hint " + (same ? "hint-ok" : "hint-bad");
}

$("signupPassword").addEventListener("input", updateStrength);
$("signupConfirmPassword").addEventListener("input", updateMatch);

/* LOGIN */
$("loginForm").addEventListener("submit", async event => {
  event.preventDefault();
  const username = $("loginUsername").value.trim();
  const password = $("loginPassword").value;
  if (!username || !password) return showMessage("loginMessage", "Enter your username/email and password.");

  setBusy($("loginSubmit"), true, "Signing in...");
  try {
    const user = await post("login", { username, password });
    showMessage("loginMessage", "Signed in. Opening your dashboard...", true);
    if (user && user.role === "STUDENT") {
      window.location.href = "/student.html";
    } else {
      window.location.href = "/index.html";
    }
  } catch (error) {
    showMessage("loginMessage", error.message);
    setBusy($("loginSubmit"), false, "Sign in");
  }
});

// Check if user is already logged in and redirect to their appropriate dashboard
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

/* SIGN UP */
$("signupForm").addEventListener("submit", async event => {
  event.preventDefault();
  const fullName = $("signupFullName").value.trim();
  const username = $("signupUsername").value.trim();
  const email = $("signupEmail").value.trim();
  const password = $("signupPassword").value;
  const confirm = $("signupConfirmPassword").value;

  if (!fullName || !username || !email || !password) return showMessage("signupMessage", "Please fill in every field.");
  if (!/^[A-Za-z0-9_.-]{3,50}$/.test(username)) return showMessage("signupMessage", "Username must be 3–50 letters, numbers, dot, underscore or hyphen.");
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) return showMessage("signupMessage", "Enter a valid email address.");
  if (password.length < 8) return showMessage("signupMessage", "Password must be at least 8 characters.");
  if (password !== confirm) return showMessage("signupMessage", "Passwords do not match.");

  setBusy($("signupSubmit"), true, "Creating account...");
  try {
    const result = await post("signup", { fullName, username, email, password });
    $("signupForm").reset();
    updateStrength();
    setMode(false);
    $("loginUsername").value = username;
    $("loginPassword").focus();
    showMessage("loginMessage", result.message || "Account created. Please sign in.", true);
  } catch (error) {
    showMessage("signupMessage", error.message);
  } finally {
    setBusy($("signupSubmit"), false, "Create account");
  }
});

// Open the sign-up form directly with login.html#signup
if (window.location.hash === "#signup") setMode(true);
