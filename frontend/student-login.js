"use strict";

// EduBridge Dedicated Student Portal Login & Registration Controller
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
      + `<div style="font-weight:700;">Access Denied</div>`
      + `<div style="margin-top:3px;font-size:12px;">${text}</div>`
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
  
  // Tab active states
  $("tabSignIn").classList.toggle("active", !signup);
  $("tabRegister").classList.toggle("active", signup);

  // Form visibility
  $("studentLoginForm").classList.toggle("hidden", signup);
  $("studentSignupForm").classList.toggle("hidden", !signup);

  // Minimal headers
  if ($("studentTitle")) $("studentTitle").textContent = signup ? "Create Account" : "Welcome Back";
  if ($("studentSubtitle")) $("studentSubtitle").textContent = signup
    ? "Register to access your student workspace"
    : "Sign in to access your courses and grades";

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

// Caps Lock detection & space bar filter for student section
(function setupInputFilters() {
  const check = event => {
    if (event.getModifierState) $("studentLoginCaps").classList.toggle("hidden", !event.getModifierState("CapsLock"));
  };
  const pw = $("studentLoginPassword");
  const user = $("studentLoginUsername");

  if (pw) {
    pw.addEventListener("keydown", check);
    pw.addEventListener("keyup", check);
    pw.addEventListener("blur", () => $("studentLoginCaps").classList.add("hidden"));
  }

  // Neglect spaces if space bar is clicked by mistake in student username or password
  [user, pw, $("studentSignupUsername"), $("studentSignupPassword"), $("studentSignupConfirm")].forEach(input => {
    if (!input) return;
    input.addEventListener("keydown", e => {
      if (e.key === " " || e.code === "Space") {
        e.preventDefault(); // Ignore space bar keypress
      }
    });
    input.addEventListener("input", e => {
      if (e.target.value.includes(" ")) {
        e.target.value = e.target.value.replace(/\s+/g, "");
      }
    });
  });
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
  const password = $("studentSignupPassword").value.replace(/\s+/g, "");
  const score = passwordScore(password);
  const labels = [
    "8+ characters recommended",
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
  const confirm = $("studentSignupConfirm").value.replace(/\s+/g, "");
  const node = $("studentMatchText");
  if (!confirm) {
    node.textContent = "";
    node.className = "field-hint";
    return;
  }
  const same = confirm === $("studentSignupPassword").value.replace(/\s+/g, "");
  node.textContent = same ? "✓ Passwords match" : "✗ Passwords do not match";
  node.className = "field-hint " + (same ? "ok" : "error");
}

$("studentSignupPassword").addEventListener("input", updateStrength);
$("studentSignupConfirm").addEventListener("input", updateMatch);

// Student login form submit with portal=STUDENT
$("studentLoginForm").addEventListener("submit", async event => {
  event.preventDefault();
  // Neglect any accidental spaces from student username and password
  const username = $("studentLoginUsername").value.replace(/\s+/g, "");
  const password = $("studentLoginPassword").value.replace(/\s+/g, "");
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
      showMessage("studentLoginMessage", body.message || "This portal is strictly reserved for students. Access denied.", false, true);
      setBusy($("studentLoginSubmit"), false, "Sign In");
      return;
    }

    if (!response.ok) {
      throw new Error(body.message || `Sign in failed (${response.status})`);
    }

    showMessage("studentLoginMessage", "Signed in. Opening your portal...", true);
    setTimeout(() => {
      window.location.href = "/student.html";
    }, 400);
  } catch (error) {
    showMessage("studentLoginMessage", error.message || "Authentication failed.");
    setBusy($("studentLoginSubmit"), false, "Sign In");
  }
});

// Student sign-up form submit
$("studentSignupForm").addEventListener("submit", async event => {
  event.preventDefault();
  const fullName = $("studentSignupFullName").value.trim();
  const username = $("studentSignupUsername").value.replace(/\s+/g, "");
  const email = $("studentSignupEmail").value.replace(/\s+/g, "");
  const password = $("studentSignupPassword").value.replace(/\s+/g, "");
  const confirm = $("studentSignupConfirm").value.replace(/\s+/g, "");

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

// ================================================================
// DYNAMIC LUMINOUS FLOATING BUBBLES & CARD TILT ENGINE
// ================================================================
(function initStudentDynamicMotion() {
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

  const canvas = $("studentParticles");
  const card = document.querySelector(".student-card");
  if (!canvas) return;

  const ctx = canvas.getContext("2d");
  let width = 0, height = 0, dpr = 1;
  let particles = [];
  let mouse = { x: -1000, y: -1000, active: false };
  let animId = null;

  const colors = [
    "rgba(56, 189, 248, ",   // sky
    "rgba(99, 102, 241, ",   // indigo
    "rgba(52, 211, 153, ",   // emerald
    "rgba(168, 85, 247, "    // purple
  ];

  function resize() {
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    width = window.innerWidth;
    height = window.innerHeight;
    canvas.width = width * dpr;
    canvas.height = height * dpr;
    canvas.style.width = width + "px";
    canvas.style.height = height + "px";
    ctx.scale(dpr, dpr);
    createParticles();
  }

  function createParticles() {
    const count = Math.min(Math.floor((width * height) / 22000), 45);
    particles = [];
    for (let i = 0; i < count; i++) {
      particles.push({
        x: Math.random() * width,
        y: Math.random() * height,
        radius: Math.random() * 3 + 1.5,
        speedY: Math.random() * 0.5 + 0.3,
        swaySpeed: Math.random() * 0.02 + 0.01,
        swayAmp: Math.random() * 1.2 + 0.6,
        angle: Math.random() * Math.PI * 2,
        color: colors[Math.floor(Math.random() * colors.length)],
        alpha: Math.random() * 0.35 + 0.2
      });
    }
  }

  function updateAndDraw() {
    ctx.clearRect(0, 0, width, height);

    for (let i = 0; i < particles.length; i++) {
      const p = particles[i];

      p.angle += p.swaySpeed;
      p.x += Math.sin(p.angle) * p.swayAmp * 0.4;
      p.y -= p.speedY;

      // Soft mouse repulsion
      if (mouse.active) {
        const dx = p.x - mouse.x;
        const dy = p.y - mouse.y;
        const dist = Math.hypot(dx, dy);
        if (dist < 110 && dist > 0) {
          const force = (110 - dist) / 110 * 0.9;
          p.x += (dx / dist) * force;
          p.y += (dy / dist) * force;
        }
      }

      // Wrap around top/bottom and edges
      if (p.y + p.radius < 0) {
        p.y = height + p.radius;
        p.x = Math.random() * width;
      }
      if (p.x < -10) p.x = width + 10;
      if (p.x > width + 10) p.x = -10;

      // Draw glowing buoyant bubble
      ctx.beginPath();
      ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
      ctx.fillStyle = p.color + p.alpha + ")";
      ctx.shadowBlur = 10;
      ctx.shadowColor = p.color + "0.5)";
      ctx.fill();
      ctx.shadowBlur = 0;
    }

    animId = requestAnimationFrame(updateAndDraw);
  }

  window.addEventListener("resize", () => {
    resize();
  });

  window.addEventListener("mousemove", e => {
    mouse.x = e.clientX;
    mouse.y = e.clientY;
    mouse.active = true;

    // Subtle 3D Card Parallax Tilt
    if (card) {
      const cx = width / 2;
      const cy = height / 2;
      const tiltX = ((e.clientY - cy) / cy) * -4;
      const tiltY = ((e.clientX - cx) / cx) * 4;
      card.style.transform = `perspective(1000px) rotateX(${tiltX.toFixed(2)}deg) rotateY(${tiltY.toFixed(2)}deg)`;
    }
  });

  window.addEventListener("mouseleave", () => {
    mouse.active = false;
    if (card) card.style.transform = "";
  });

  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      if (animId) cancelAnimationFrame(animId);
    } else {
      animId = requestAnimationFrame(updateAndDraw);
    }
  });

  resize();
  animId = requestAnimationFrame(updateAndDraw);
})();

