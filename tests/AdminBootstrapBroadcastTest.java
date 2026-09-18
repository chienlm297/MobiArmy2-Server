package mobiarmy.admin;

import com.google.gson.*;
import java.io.*;
import java.net.Socket;
import mobiarmy.io.Message;
import mobiarmy.server.*;

/** Runs only against the separate admin_migration_test database in the disposable stack. */
public class AdminBootstrapBroadcastTest {
    public static class RecordingSession extends Session {
        int sends;
        int command;
        byte[] payload;
        // Gson allocates this test double without starting Session's network threads.
        RecordingSession(Socket socket) throws IOException { super(socket, 1); }
        @Override public void sendMessage(Message message) {
            sends++;
            command = message.getCommand();
            payload = message.getData();
        }
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        System.out.println("PASS: " + message);
    }
    public static void main(String[] args) throws Exception {
        if (!System.getenv("DB_URL").contains("/admin_migration_test?"))
            throw new IllegalStateException("Requires disposable migration database");
        AdminAccounts.initialize("firstowner", "firstpassword");
        check(AdminAccounts.login("firstowner", "firstpassword").role() == AdminAccounts.Role.OWNER,
                "missing admin table auto-created and OWNER bootstrapped");
        AdminAccounts.initialize("otherowner", "otherpassword");
        check(AdminAccounts.login("firstowner", "firstpassword") != null && AdminAccounts.list().size() == 1,
                "restart does not overwrite persisted credentials");
        check(AdminAccounts.login("otherowner", "otherpassword") == null, "changed environment does not create another OWNER");
        Gson gson = new GsonBuilder().setExclusionStrategies(new ExclusionStrategy() {
            public boolean shouldSkipField(FieldAttributes f) {
                return !f.getDeclaredClass().isPrimitive() && f.getDeclaredClass() != String.class;
            }
            public boolean shouldSkipClass(Class<?> type) { return false; }
        }).create();
        RecordingSession online = gson.fromJson("{\"connected\":true}", RecordingSession.class);
        RecordingSession offline = gson.fromJson("{\"connected\":false}", RecordingSession.class);
        online.sessionHandler = new SessionHandler(online);
        offline.sessionHandler = new SessionHandler(offline);
        User first = gson.fromJson("{\"id\":1,\"name\":\"online\"}", User.class);
        User second = gson.fromJson("{\"id\":2,\"name\":\"offline\"}", User.class);
        User third = gson.fromJson("{\"id\":3,\"name\":\"bot\"}", User.class);
        first.session = online;
        second.session = offline;
        SessionManager.addUser(first);
        SessionManager.addUser(second);
        SessionManager.addUser(third);
        check(AdminOperations.broadcast("Xin chào người chơi", "test packet", "firstowner") == 1,
                "broadcast counts only connected player sessions");
        check(online.sends == 1 && offline.sends == 0, "offline users and bots skipped");
        check(online.command == 46 && new DataInputStream(new ByteArrayInputStream(online.payload)).readUTF()
                .equals("[Thông báo] Xin chào người chơi"), "world message uses command 46 and UTF game payload");
        try (var c = Server.dbManager.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("RENAME TABLE admin_audit_log TO unavailable_audit_log");
            try {
                AdminOperations.broadcast("Must not send", "audit unavailable", "firstowner");
                throw new AssertionError("Expected audit failure");
            } catch (java.sql.SQLException expected) {
                check(online.sends == 1, "audit failure prevents broadcast delivery");
            } finally {
                s.executeUpdate("RENAME TABLE unavailable_audit_log TO admin_audit_log");
            }
        }
        System.out.println("7 bootstrap/broadcast checks passed");
    }
}
