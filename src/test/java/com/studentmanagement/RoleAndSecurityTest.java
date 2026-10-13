package com.studentmanagement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RBAC Unit Tests: Roles, UserAccount, and Session Management")
public class RoleAndSecurityTest {

    @Test
    @DisplayName("Role enum contains exactly ADMIN and STUDENT")
    void testRoleEnum() {
        assertEquals(2, Role.values().length);
        assertEquals(Role.ADMIN, Role.valueOf("ADMIN"));
        assertEquals(Role.STUDENT, Role.valueOf("STUDENT"));
    }

    @Test
    @DisplayName("UserAccount encapsulation and getters verify state correctly")
    void testUserAccountEncapsulation() {
        UserAccount admin = new UserAccount(1, "System Administrator", "sysadmin", "admin@campus.edu", Role.ADMIN, null);
        assertEquals(1, admin.getUserId());
        assertEquals("System Administrator", admin.getFullName());
        assertEquals("sysadmin", admin.getUsername());
        assertEquals("admin@campus.edu", admin.getEmail());
        assertEquals(Role.ADMIN, admin.getRole());
        assertNull(admin.getStudentId());

        UserAccount student = new UserAccount(2, "Raj Sharma", "raj101", "raj@campus.edu", Role.STUDENT, 101);
        assertEquals(2, student.getUserId());
        assertEquals("Raj Sharma", student.getFullName());
        assertEquals("raj101", student.getUsername());
        assertEquals("raj@campus.edu", student.getEmail());
        assertEquals(Role.STUDENT, student.getRole());
        assertEquals(101, student.getStudentId());
    }

    @Test
    @DisplayName("Backward compatibility constructor defaults to null studentId")
    void testLegacyConstructorCompatibility() {
        UserAccount legacy = new UserAccount(9, "Legacy Student", "legacy9", "legacy@campus.edu");
        assertEquals(Role.STUDENT, legacy.getRole());
        assertNull(legacy.getStudentId());
    }

    @Test
    @DisplayName("Session token lifecycle: login, creation, retrieval, and logout invalidation")
    void testSessionTokenLifecycle() throws Exception {
        AuthManager auth = new AuthManager();
        UserAccount user = auth.login("sysadmin", "AdminPass123!");
        assertNotNull(user);

        String token = auth.createSession(user);
        assertNotNull(token);
        assertFalse(token.isEmpty());

        UserAccount retrieved = auth.getUser(token);
        assertNotNull(retrieved);
        assertEquals("sysadmin", retrieved.getUsername());
        assertEquals(Role.ADMIN, retrieved.getRole());

        auth.logout(token);
        assertNull(auth.getUser(token), "Session must be invalidated upon logout");
    }

    @Test
    @DisplayName("Dynamic session refresh reflects student account linkage updates")
    void testDynamicSessionRefresh() throws Exception {
        AuthManager auth = new AuthManager();
        UserAccount raj = auth.login("raj101", "StudentPass123!");
        assertNotNull(raj);

        String token = auth.createSession(raj);
        assertEquals(101, auth.getUser(token).getStudentId());

        // Refresh link in session
        auth.refreshUserStudentLink(raj.getUserId(), 101);
        assertEquals(101, auth.getUser(token).getStudentId());
    }
}
