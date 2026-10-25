package com.codelab.app.migration;

import com.codelab.app.config.AppProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 极简迁移器（PRD §13.6）：按文件名升序执行 deploy/db/*.sql，已执行版本跳过。
 * 刻意不用 Flyway：PRD 说"手工 SQL 脚本按序执行（Flyway 可选）"，这点逻辑不值得加依赖。
 * 每个脚本在单个事务里执行，失败即整体回滚，不会留下半张表。
 */
@Component
@Order(1)
public class MigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);
    private static final List<String> FALLBACK_DIRS = List.of(
            "../../deploy/db", "../deploy/db", "deploy/db", "../../../deploy/db");

    private final DataSource dataSource;
    private final AppProperties props;

    public MigrationRunner(DataSource dataSource, AppProperties props) {
        this.dataSource = dataSource;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path dir = resolveDir();
        List<Path> files = sqlFiles(dir);
        log.info("数据库迁移目录: {}，共 {} 个脚本", dir.toAbsolutePath(), files.size());
        try (Connection conn = dataSource.getConnection()) {
            ensureMigrationsTable(conn);
            Set<String> applied = appliedVersions(conn);
            int executed = 0;
            for (Path file : files) {
                String version = file.getFileName().toString();
                if (applied.contains(version)) {
                    continue;
                }
                apply(conn, file, version);
                executed++;
            }
            log.info("数据库迁移完成：本次执行 {} 个，已是最新 {} 个", executed, applied.size());
        } catch (SQLException ex) {
            throw new IllegalStateException("数据库迁移失败: " + ex.getMessage(), ex);
        }
    }

    private void apply(Connection conn, Path file, String version) throws SQLException {
        String sql;
        try {
            sql = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("读取迁移脚本失败: " + file, ex);
        }
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO schema_migrations (version) VALUES (?)")) {
                ps.setString(1, version);
                ps.executeUpdate();
            }
            conn.commit();
            log.info("已执行迁移 {}", version);
        } catch (SQLException ex) {
            conn.rollback();
            throw new IllegalStateException("迁移脚本执行失败: " + version + " -> " + ex.getMessage(), ex);
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    private void ensureMigrationsTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                      version    text PRIMARY KEY,
                      applied_at timestamptz NOT NULL DEFAULT now()
                    )""");
        }
    }

    private Set<String> appliedVersions(Connection conn) throws SQLException {
        Set<String> versions = new HashSet<>();
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT version FROM schema_migrations")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        }
        return versions;
    }

    private Path resolveDir() {
        String configured = props.migration() == null ? null : props.migration().dir();
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            if (!Files.isDirectory(path)) {
                throw new IllegalStateException("APP_DB_MIGRATION_DIR 指向的目录不存在: " + path.toAbsolutePath());
            }
            return path;
        }
        for (String candidate : FALLBACK_DIRS) {
            Path path = Path.of(candidate);
            if (Files.isDirectory(path) && !sqlFiles(path).isEmpty()) {
                return path;
            }
        }
        throw new IllegalStateException("找不到迁移脚本目录，请设置 APP_DB_MIGRATION_DIR（试过 " + FALLBACK_DIRS + "）");
    }

    private List<Path> sqlFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".sql"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(files::add);
        } catch (IOException ex) {
            throw new IllegalStateException("列举迁移脚本失败: " + dir, ex);
        }
        return files;
    }
}
