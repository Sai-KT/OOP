"use strict";

// CampusFlow Dedicated Administrator Login Controller
const $ = id => document.getElementById(id);

function showMessage(text, success = false, isStudentAttempt = false) {
  const node = $("adminMessage");
  if (!text) {
    node.innerHTML = "";
    node.classList.add("hidden");
    return;
  }
  if (isStudentAttempt) {
    node.innerHTML = `<div><strong>Access Denied: Student Account Detected</strong></div>`
      + `<div style="margin-top:4px;">${text}</div>`
      + `<div style="margin-top:8px;"><a href="/student-login.html" style="color:#7057e8;font-weight:750;text-decoration:underline;">Click here to sign in via the Student Portal →</a></div>`;
  } else {
    node.textContent = text;
  }
  node.classList.toggle("success", success);
  node.classList.remove("hidden");
}

function setBusy(busy, label) {
  const btn = $("adminSubmit");
  btn.disabled = busy;
  btn.querySelector("span").textContent = label;
}

// Password toggle (eye icon)
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
    if (event.getModifierState) $("adminCaps").classList.toggle("hidden", !event.getModifierState("CapsLock"));
  };
  const pw = $("adminPassword");
  pw.addEventListener("keydown", check);
  pw.addEventListener("keyup", check);
  pw.addEventListener("blur", () => $("adminCaps").classList.add("hidden"));
})();

// Form submission to /api/auth/login with portal=ADMIN
$("adminLoginForm").addEventListener("submit", async event => {
  event.preventDefault();
  const username = $("adminUsername").value.trim();
  const password = $("adminPassword").value;
  if (!username || !password) {
    showMessage("Please enter your administrator username and password.");
    return;
  }

  setBusy(true, "Verifying admin access...");
  try {
    const response = await fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ username, password, portal: "ADMIN" })
    });

    let body = {};
    try { body = await response.json(); } catch {}

    if (response.status === 403) {
      // Forbidden: student account attempted to sign in on admin portal
      showMessage(body.message || "This portal is strictly reserved for Administrators. Students must use the Student Portal.", false, true);
      setBusy(false, "Sign in to Admin Console");
      return;
    }

    if (!response.ok) {
      throw new Error(body.message || `Sign in failed (${response.status})`);
    }

    showMessage("Administrator authenticated. Opening Admin Dashboard...", true);
    setTimeout(() => {
      window.location.href = "/index.html";
    }, 400);
  } catch (error) {
    showMessage(error.message || "Authentication failed. Check credentials.");
    setBusy(false, "Sign in to Admin Console");
  }
});

// Check if user already has an active session and redirect accordingly
(async function checkExistingSession() {
  try {
    const response = await fetch("/api/auth/me");
    if (response.ok) {
      const user = await response.json();
      if (user?.role === "ADMIN") {
        window.location.href = "/index.html";
      } else if (user?.role === "STUDENT") {
        window.location.href = "/student.html";
      }
    }
  } catch {}
})();
