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
  const icon = success 
    ? `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0;margin-top:2px;"><polyline points="20 6 9 17 4 12"/></svg>`
    : `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0;margin-top:2px;"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>`;

  if (isStudentAttempt) {
    node.innerHTML = `${icon}<div>`
      + `<div style="font-weight:700;">Access Denied &middot; Student Account</div>`
      + `<div style="margin-top:3px;font-size:12px;">${text}</div>`
      + `<div style="margin-top:8px;"><a href="/student-login.html" style="color:#818cf8;font-weight:700;text-decoration:underline;">Click here to sign in via the Student Portal &rarr;</a></div>`
      + `</div>`;
  } else {
    node.innerHTML = `${icon}<div>${text}</div>`;
  }
  node.classList.toggle("success", success);
  node.classList.toggle("error", !success);
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
