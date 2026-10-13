"use strict";

// CampusFlow Portal Gateway Controller
// Automatically forwards logged-in users to their dedicated dashboard
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
