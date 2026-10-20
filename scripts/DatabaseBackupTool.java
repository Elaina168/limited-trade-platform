import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 本项目MySQL基础表的备份、全新库恢复和逐表核对入口。 */
class DatabaseBackupTool {
    private final Properties credentials;
    private final URI uri;
    private final String source;
    private final Path bin;
    private final Path directory;

    DatabaseBackupTool(Path config, Path bin, Path directory) throws Exception {
        credentials = read(config);
        String url = required(credentials, "db.url");
        if (!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("只支持单个MySQL TCP地址");
        uri = URI.create(url.substring(5));
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getQuery() != null && uri.getQuery().toLowerCase(Locale.ROOT).matches(".*(password|user)=.*"))) {
            throw new IllegalArgumentException("URL必须使用独立用户名密码配置");
        }
        source = uri.getPath().substring(1);
        identifier(source);
        this.bin = bin;
        this.directory = directory;
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        Path directory = Path.of(args[3]);
        try {
            var tool = new DatabaseBackupTool(Path.of(args[1]), Path.of(args[2]), directory);
            switch (args[0]) {
                case "Backup" -> tool.backup();
                case "Restore" -> tool.restore(args.length > 4 ? args[4] : "");
                case "Verify" -> tool.verify(args.length > 4 && !args[4].isBlank() ? args[4] : tool.source);
                default -> throw new IllegalArgumentException("未知操作");
            }
        } catch (Exception failure) {
            // 边界处完整保留诊断，控制台不回显可能带SQL数据或连接信息的异常。
            try (var writer = new PrintWriter(Files.newBufferedWriter(directory.resolve("tool-error.log"), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                writer.println(Instant.now());
                failure.printStackTrace(writer);
            }
            System.err.println("FAILED " + (failure instanceof CheckFailure ? failure.getMessage() : failure.getClass().getSimpleName())
                    + "; 查看私有备份目录tool-error.log");
            System.exit(1);
        }
    }

    private Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(url(database), required(credentials, "db.username"), required(credentials, "db.password"));
    }

    private String url(String database) {
        identifier(database);
        return "jdbc:mysql://" + uri.getRawAuthority() + "/" + database
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    private void backup() throws Exception {
        Path sql = directory.resolve("database.sql");
        if (Files.exists(sql) || Files.exists(directory.resolve("manifest.properties"))) throw new CheckFailure("BACKUP_EXISTS");
        Properties before = snapshot(source);
        List<String> args = new ArrayList<>(List.of("--single-transaction", "--quick", "--no-tablespaces",
                "--set-gtid-purged=OFF", "--column-statistics=0", "--default-character-set=utf8mb4", "--hex-blob",
                "--skip-comments", "--skip-add-drop-table", "--result-file=" + sql, source));
        nativeCommand("mysqldump.exe", args, null);
        compare(before, snapshot(source));
        before.setProperty("format", "limited-trade-backup-v1");
        before.setProperty("source.database", source);
        before.setProperty("created.utc", Instant.now().toString());
        before.setProperty("sql.sha256", fileHash(sql));
        write(directory.resolve("manifest.properties"), before);
        System.out.println("BACKUP_OK " + directory + "; tables=" + before.getProperty("tables"));
    }

    private Properties checkedManifest() throws Exception {
        Properties manifest = read(directory.resolve("manifest.properties"));
        if (!required(manifest, "format").equals("limited-trade-backup-v1")) throw new CheckFailure("FORMAT");
        if (!fileHash(directory.resolve("database.sql")).equals(required(manifest, "sql.sha256"))) throw new CheckFailure("CHECKSUM");
        if (!source.equals(required(manifest, "source.database"))) throw new CheckFailure("SOURCE_CONFIG");
        return manifest;
    }

    private void restore(String target) throws Exception {
        if (!target.matches("limited_trade_restore_[a-z0-9_]{1,42}") || target.equals(source)) throw new CheckFailure("TARGET_NAME");
        Properties manifest = checkedManifest();
        String charset = required(manifest, "charset");
        String collation = required(manifest, "collation");
        identifier(charset);
        identifier(collation);
        // CREATE不使用IF NOT EXISTS；已有目标不能被导入覆盖。
        try (Connection c = connect(source); Statement s = c.createStatement()) {
            try {
                s.executeUpdate("CREATE DATABASE " + quote(target) + " CHARACTER SET " + charset + " COLLATE " + collation);
            } catch (SQLException failure) {
                if (failure.getErrorCode() == 1007) throw new CheckFailure("TARGET_EXISTS");
                throw failure;
            }
        }
        System.out.println("CREATED " + target + "; 失败时保留此隔离库，不自动删除");
        nativeCommand("mysql.exe", List.of("--default-character-set=utf8mb4", "--database=" + target), directory.resolve("database.sql"));
        compare(manifest, snapshot(target));
        Properties application = new Properties();
        application.setProperty("spring.datasource.url", url(target));
        write(directory.resolve("application-" + target + ".properties"), application);
        System.out.println("RESTORE_OK " + target);
    }

    private void verify(String database) throws Exception {
        if (!database.equals(source) && !database.matches("limited_trade_restore_[a-z0-9_]{1,42}")) throw new CheckFailure("TARGET_NAME");
        compare(checkedManifest(), snapshot(database));
        System.out.println("VERIFY_OK " + database + "; 所有表结构、行数、数据摘要相同");
    }

    private Properties snapshot(String database) throws Exception {
        Properties result = new Properties();
        try (Connection c = connect(database); Statement s = c.createStatement()) {
            s.execute("SET time_zone = '+00:00'");
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try (ResultSet r = s.executeQuery("SELECT DEFAULT_CHARACTER_SET_NAME,DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=DATABASE()")) {
                if (!r.next()) throw new CheckFailure("SCHEMA_MISSING");
                result.setProperty("charset", r.getString(1));
                result.setProperty("collation", r.getString(2));
            }
            for (String object : List.of("TRIGGERS", "ROUTINES", "EVENTS")) {
                String column = object.equals("TRIGGERS") ? "TRIGGER_SCHEMA" : object.equals("ROUTINES") ? "ROUTINE_SCHEMA" : "EVENT_SCHEMA";
                try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM information_schema." + object + " WHERE " + column + "=DATABASE()")) {
                    r.next();
                    if (r.getLong(1) != 0) throw new CheckFailure("UNSUPPORTED_" + object);
                }
            }
            try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_SCHEMA<>DATABASE()")) {
                r.next();
                if (r.getLong(1) != 0) throw new CheckFailure("CROSS_SCHEMA_FOREIGN_KEY");
            }
            List<String> tables = new ArrayList<>();
            try (ResultSet r = s.executeQuery("SELECT TABLE_NAME,ENGINE,TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME")) {
                while (r.next()) {
                    if (!"InnoDB".equals(r.getString(2)) || !"BASE TABLE".equals(r.getString(3))) throw new CheckFailure("UNSUPPORTED_TABLE");
                    identifier(r.getString(1));
                    tables.add(r.getString(1));
                }
            }
            result.setProperty("tables", String.join(",", tables));
            for (String table : tables) {
                String key = "table." + table;
                try (ResultSet r = s.executeQuery("SHOW CREATE TABLE " + quote(table))) {
                    r.next();
                    result.setProperty(key + ".ddl", hash(r.getString(2).getBytes(StandardCharsets.UTF_8)));
                }
                SortedMap<Integer, String> primary = new TreeMap<>();
                try (ResultSet r = c.getMetaData().getPrimaryKeys(database, null, table)) {
                    while (r.next()) primary.put(r.getInt("KEY_SEQ"), quote(r.getString("COLUMN_NAME")));
                }
                if (primary.isEmpty()) throw new CheckFailure("PRIMARY_KEY_REQUIRED");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long count = 0;
                try (ResultSet r = s.executeQuery("SELECT * FROM " + quote(table) + " ORDER BY " + String.join(",", primary.values()))) {
                    int columns = r.getMetaData().getColumnCount();
                    while (r.next()) {
                        count++;
                        for (int column = 1; column <= columns; column++) {
                            byte[] value = r.getBytes(column);
                            // 长度前缀区分NULL、空值和内容中的分隔符。
                            digest.update(ByteBuffer.allocate(4).putInt(value == null ? -1 : value.length).array());
                            if (value != null) digest.update(value);
                        }
                    }
                }
                result.setProperty(key + ".rows", Long.toString(count));
                result.setProperty(key + ".data", HexFormat.of().formatHex(digest.digest()));
            }
            c.rollback();
        }
        return result;
    }

    private static void compare(Properties expected, Properties actual) throws CheckFailure {
        Set<String> keys = new TreeSet<>(actual.stringPropertyNames());
        Set<String> expectedKeys = new TreeSet<>(expected.stringPropertyNames());
        expectedKeys.removeAll(Set.of("format", "source.database", "created.utc", "sql.sha256"));
        if (!keys.equals(expectedKeys)) throw new CheckFailure("MANIFEST_KEYS");
        for (String key : keys) if (!actual.getProperty(key).equals(expected.getProperty(key))) throw new CheckFailure("MISMATCH " + key);
    }

    private void nativeCommand(String program, List<String> options, Path input) throws Exception {
        Path defaults = Files.createTempFile(directory, "mysql-credentials-", ".cnf");
        try {
            Files.writeString(defaults, "[client]\npassword=\"" + optionValue(required(credentials, "db.password")) + "\"\n", StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>(List.of(bin.resolve(program).toString(), "--defaults-file=" + defaults,
                    "--protocol=TCP", "--host=" + uri.getHost(), "--port=" + (uri.getPort() < 0 ? 3306 : uri.getPort()),
                    "--user=" + required(credentials, "db.username")));
            command.addAll(options);
            ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(directory.resolve(program + ".stdout.log").toFile())
                    .redirectError(directory.resolve(program + ".stderr.log").toFile());
            if (input != null) builder.redirectInput(input.toFile());
            Process process = builder.start();
            try {
                if (!process.waitFor(60, TimeUnit.SECONDS)) throw new CheckFailure("MYSQL_TIMEOUT");
                if (process.exitValue() != 0) throw new CheckFailure("MYSQL_EXIT_" + process.exitValue());
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            }
        } finally {
            Files.delete(defaults);
        }
    }

    private static String optionValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String required(Properties p, String key) {
        return Objects.requireNonNull(p.getProperty(key), "缺少配置 " + key);
    }

    private static void identifier(String name) {
        if (!name.matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("不支持的数据库标识符");
    }

    private static String quote(String name) {
        identifier(name);
        return "`" + name + "`";
    }

    private static Properties read(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
        return p;
    }

    private static void write(Path file, Properties properties) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            properties.store(writer, "本机备份清单，不包含连接密码");
        }
    }

    private static String fileHash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = stream.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static class CheckFailure extends Exception {
        CheckFailure(String message) { super(message); }
    }
}
