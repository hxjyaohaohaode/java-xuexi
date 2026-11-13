package io.typeforge.operations.migrations.h2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Local/test SQL adapter only. MySQL executes the unmodified, versioned source migrations. */
abstract class PortableMigration extends BaseJavaMigration {
    private final String source;
    protected PortableMigration(String source) { this.source = source; }
    private String sourceText() {
        try { return new ClassPathResource("database/" + source).getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException e) { throw new IllegalStateException("Missing database migration", e); }
    }
    @Override public Integer getChecksum() { return sourceText().hashCode(); }
    @Override public void migrate(Context context) {
        String sql = sourceText()
            .replaceAll("(?m)^SET NAMES .*;\\s*$", "")
            .replaceAll("(?m)^SET time_zone .*;\\s*$", "")
            .replaceAll("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin", "");
        ScriptUtils.executeSqlScript(context.getConnection(), new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
    }
}
