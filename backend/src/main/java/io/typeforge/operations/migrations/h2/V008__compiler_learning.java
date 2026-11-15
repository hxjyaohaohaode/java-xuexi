package io.typeforge.operations.migrations.h2;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

public final class V008__compiler_learning extends PortableMigration {
    public V008__compiler_learning() { super("V008__compiler_learning.sql"); }

    /** H2's unnamed CHECK identifiers differ from the documented MySQL table_chk_1 convention. */
    @Override public void migrate(Context context) {
        try {
            String constraint;
            try (var query=context.getConnection().prepareStatement("SELECT constraint_name FROM information_schema.table_constraints WHERE LOWER(table_name)='notebook_action_receipt' AND constraint_type='CHECK' AND table_schema=CURRENT_SCHEMA()")) {
                try (var rows=query.executeQuery()) {
                    if (!rows.next()) throw new SQLException("V006 notebook action check is missing");
                    constraint=rows.getString(1);
                    if (rows.next()) throw new SQLException("Unexpected multiple V006 notebook action checks");
                }
            }
            String sql=new ClassPathResource("database/V008__compiler_learning.sql").getContentAsString(StandardCharsets.UTF_8)
                .replace("ALTER TABLE notebook_action_receipt DROP CHECK notebook_action_receipt_chk_1;",
                    "ALTER TABLE notebook_action_receipt DROP CONSTRAINT \""+constraint.replace("\"","\"\"")+"\";")
                .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin", "");
            ScriptUtils.executeSqlScript(context.getConnection(),new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        } catch (java.io.IOException | SQLException failure) {
            throw new IllegalStateException("Could not adapt the V008 notebook action check for H2",failure);
        }
    }
}
