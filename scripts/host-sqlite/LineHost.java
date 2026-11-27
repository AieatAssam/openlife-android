import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * HostSqlite's line protocol over any sqlite-jdbc jar, for running the vault
 * host tests on a SQLite other than the pinned one (ADR-0017; engine bisect in
 * docs/verification/P2-08-local-search.md). Local procedure only, never CI.
 *
 * <pre>
 * OPENLIFE_HOST_SQLITE="java -cp /abs/sqlite-jdbc-3.45.1.0.jar:/abs/slf4j-api.jar \
 *   /abs/openlife/scripts/host-sqlite/LineHost.java" ./gradlew :vault:testDebugUnitTest
 * </pre>
 *
 * One request per line: the SQL as hex, then one token per bound value
 * (n, i:long, r:double, b:hex, s:utf8-hex). Reply: OK or ERR hex(message),
 * one line per row, then END. Nothing is logged.
 */
public final class LineHost {
    private LineHost() {}

    public static void main(String[] args) throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + (args.length > 0 ? args[0] : ":memory:"));
        connection.setAutoCommit(true);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA recursive_triggers=ON");
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, "UTF-8");
        String line;
        while ((line = in.readLine()) != null) {
            String[] parts = line.trim().split(" ");
            if (parts[0].isEmpty()) continue;
            List<String> reply = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    new String(unhex(parts[0]), StandardCharsets.UTF_8))) {
                for (int i = 1; i < parts.length; i++) statement.setObject(i, decode(parts[i]));
                reply.add("OK");
                if (statement.execute()) {
                    ResultSet rows = statement.getResultSet();
                    int columns = rows.getMetaData().getColumnCount();
                    while (rows.next()) {
                        StringJoiner row = new StringJoiner(" ");
                        for (int i = 1; i <= columns; i++) row.add(encode(rows.getObject(i)));
                        reply.add(row.toString());
                    }
                }
            } catch (Exception e) {
                reply.clear();
                reply.add("ERR " + hex(String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8)));
            }
            reply.add("END");
            out.print(String.join("\n", reply) + "\n");
            out.flush();
        }
    }

    private static byte[] unhex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format("%02x", b));
        return text.toString();
    }

    private static Object decode(String token) {
        int colon = token.indexOf(':');
        String kind = colon < 0 ? token : token.substring(0, colon);
        String body = colon < 0 ? "" : token.substring(colon + 1);
        switch (kind) {
            case "n": return null;
            case "i": return Long.parseLong(body);
            case "r": return Double.parseDouble(body);
            case "b": return unhex(body);
            default: return new String(unhex(body), StandardCharsets.UTF_8);
        }
    }

    private static String encode(Object value) {
        if (value == null) return "n";
        if (value instanceof Integer || value instanceof Long) return "i:" + value;
        if (value instanceof Number) return "r:" + value;
        if (value instanceof byte[]) return "b:" + hex((byte[]) value);
        return "s:" + hex(value.toString().getBytes(StandardCharsets.UTF_8));
    }
}
