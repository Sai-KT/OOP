"use strict";

// EduBridge Dedicated Administrator Login Controller
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

// Caps Lock watcher & space bar filter
(function setupInputFilters() {
  const check = event => {
    if (event.getModifierState) $("adminCaps").classList.toggle("hidden", !event.getModifierState("CapsLock"));
  };
  const pw = $("adminPassword");
  const user = $("adminUsername");

  pw.addEventListener("keydown", check);
  pw.addEventListener("keyup", check);
  pw.addEventListener("blur", () => $("adminCaps").classList.add("hidden"));

  // Neglect spaces if space bar clicked by mistake in username or password
  [user, pw].forEach(input => {
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

// Form submission to /api/auth/login with portal=ADMIN
$("adminLoginForm").addEventListener("submit", async event => {
  event.preventDefault();
  // Strip any accidental spaces from username and password
  const username = $("adminUsername").value.replace(/\s+/g, "");
  const password = $("adminPassword").value.replace(/\s+/g, "");
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

// ================================================================
// DYNAMIC CYBER CONSTELLATION & CARD TILT ENGINE
// ================================================================
(function initAdminDynamicMotion() {
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

  const canvas = $("adminParticles");
  const card = document.querySelector(".admin-card");
  if (!canvas) return;

  const ctx = canvas.getContext("2d");
  let width = 0, height = 0, dpr = 1;
  let particles = [];
  let mouse = { x: -1000, y: -1000, active: false };
  let animId = null;

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
    const count = Math.min(Math.floor((width * height) / 18000), 55);
    particles = [];
    for (let i = 0; i < count; i++) {
      particles.push({
        x: Math.random() * width,
        y: Math.random() * height,
        vx: (Math.random() - 0.5) * 0.7,
        vy: (Math.random() - 0.5) * 0.7,
        radius: Math.random() * 1.8 + 1,
        color: Math.random() > 0.4 ? "rgba(129, 140, 248, " : "rgba(52, 211, 153, ",
        alpha: Math.random() * 0.5 + 0.3
      });
    }
  }

  function updateAndDraw() {
    ctx.clearRect(0, 0, width, height);

    // Update positions & draw particles
    for (let i = 0; i < particles.length; i++) {
      const p = particles[i];

      // Subtle mouse interaction
      if (mouse.active) {
        const dx = p.x - mouse.x;
        const dy = p.y - mouse.y;
        const dist = Math.hypot(dx, dy);
        if (dist < 130 && dist > 0) {
          const force = (130 - dist) / 130 * 0.8;
          p.x += (dx / dist) * force;
          p.y += (dy / dist) * force;
        }
      }

      p.x += p.vx;
      p.y += p.vy;

      if (p.x < 0) { p.x = 0; p.vx *= -1; }
      else if (p.x > width) { p.x = width; p.vx *= -1; }
      if (p.y < 0) { p.y = 0; p.vy *= -1; }
      else if (p.y > height) { p.y = height; p.vy *= -1; }

      ctx.beginPath();
      ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
      ctx.fillStyle = p.color + p.alpha + ")";
      ctx.shadowBlur = 8;
      ctx.shadowColor = p.color + "0.6)";
      ctx.fill();
      ctx.shadowBlur = 0;

      // Draw connection lines between close particles
      for (let j = i + 1; j < particles.length; j++) {
        const p2 = particles[j];
        const dist = Math.hypot(p.x - p2.x, p.y - p2.y);
        if (dist < 110) {
          const lineAlpha = (1 - dist / 110) * 0.22;
          ctx.beginPath();
          ctx.moveTo(p.x, p.y);
          ctx.lineTo(p2.x, p2.y);
          ctx.strokeStyle = `rgba(129, 140, 248, ${lineAlpha})`;
          ctx.lineWidth = 0.8;
          ctx.stroke();
        }
      }

      // Draw line to cursor if close
      if (mouse.active) {
        const mDist = Math.hypot(p.x - mouse.x, p.y - mouse.y);
        if (mDist < 120) {
          const mAlpha = (1 - mDist / 120) * 0.35;
          ctx.beginPath();
          ctx.moveTo(p.x, p.y);
          ctx.lineTo(mouse.x, mouse.y);
          ctx.strokeStyle = `rgba(99, 102, 241, ${mAlpha})`;
          ctx.lineWidth = 1;
          ctx.stroke();
        }
      }
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

