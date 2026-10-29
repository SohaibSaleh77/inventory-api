package com.ims.testing;

import com.ims.domain.user.User;
import com.ims.domain.user.UserDao;
import com.ims.security.AuthContext;
import com.ims.security.Role;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixture users for each role + helpers to build AuthContexts directly
 * (services accept AuthContext, so tests skip HTTP where the unit under
 * test is the service).
 */
public final class TestUsers {

    public static final String PASSWORD = "TestPass!42x";
    private static final AtomicLong SUFFIX = new AtomicLong(
            System.currentTimeMillis() % 100000);

    public final long adminId;
    public final long managerId;
    public final long staffId;
    public final long viewerId;

    /** Actual (stamped) usernames, for logging in through the real API. */
    public final String adminUsername;
    public final String managerUsername;
    public final String staffUsername;
    public final String viewerUsername;

    public final AuthContext admin;
    public final AuthContext manager;
    public final AuthContext staff;
    public final AuthContext viewer;

    private TestUsers(long adminId, long managerId, long staffId, long viewerId,
                     String adminUsername, String managerUsername,
                     String staffUsername, String viewerUsername) {
        this.adminId = adminId;
        this.managerId = managerId;
        this.staffId = staffId;
        this.viewerId = viewerId;
        this.adminUsername = adminUsername;
        this.managerUsername = managerUsername;
        this.staffUsername = staffUsername;
        this.viewerUsername = viewerUsername;
        this.admin = new AuthContext(adminId, "admin", Role.ADMIN, 0);
        this.manager = new AuthContext(managerId, "manager", Role.MANAGER, 0);
        this.staff = new AuthContext(staffId, "staff", Role.STAFF, 0);
        this.viewer = new AuthContext(viewerId, "viewer", Role.VIEWER, 0);
    }

    /** Creates the four role users with unique usernames (safe per truncate). */
    public static TestUsers create(IntegrationTestEnv env) {
        UserDao userDao = new UserDao();
        long stamp = SUFFIX.incrementAndGet();
        String adminName = "admin_" + stamp;
        String managerName = "manager_" + stamp;
        String staffName = "staff_" + stamp;
        String viewerName = "viewer_" + stamp;
        return env.transactions.inTransaction(conn -> {
            try {
                long admin = insert(userDao, conn, adminName, Role.ADMIN);
                long manager = insert(userDao, conn, managerName, Role.MANAGER);
                long staff = insert(userDao, conn, staffName, Role.STAFF);
                long viewer = insert(userDao, conn, viewerName, Role.VIEWER);
                return new TestUsers(admin, manager, staff, viewer,
                        adminName, managerName, staffName, viewerName);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static long insert(UserDao userDao, java.sql.Connection conn,
                               String username, Role role) throws SQLException {
        String hash = envHash();
        User user = new User(username, username + "@test.example", hash,
                username + " (test)", role);
        userDao.insert(conn, user);
        return user.getId();
    }

    private static String envHash() {
        return Holder.HASHER.hash(PASSWORD);
    }

    public boolean passwordMatches(String candidate) {
        return Holder.HASHER.verify(candidate, envHash());
    }

    /** Lazy holder so the hasher is built once. */
    private static final class Holder {
        static final com.ims.security.PasswordHasher HASHER =
                new com.ims.security.PasswordHasher(120000, 16);
    }
}
