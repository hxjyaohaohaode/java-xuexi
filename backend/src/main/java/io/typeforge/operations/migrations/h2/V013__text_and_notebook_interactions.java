package io.typeforge.operations.migrations.h2;

import java.nio.charset.StandardCharsets;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

public final class V013__text_and_notebook_interactions extends PortableMigration {
    public V013__text_and_notebook_interactions(){super("V013__text_and_notebook_interactions.sql");}
    @Override public void migrate(Context context) {
        try {
            String sql=new ClassPathResource("database/V013__text_and_notebook_interactions.sql").getContentAsString(StandardCharsets.UTF_8)
                .replace("DROP CHECK ck_notebook_action_supported","DROP CONSTRAINT ck_notebook_action_supported")
                .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin","");
            ScriptUtils.executeSqlScript(context.getConnection(),new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        } catch(java.io.IOException failure){throw new IllegalStateException("Cannot load V013 notebook/text migration",failure);}
    }
}
