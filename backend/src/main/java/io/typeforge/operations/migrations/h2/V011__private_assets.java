package io.typeforge.operations.migrations.h2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** The MySQL source discovers its CHECK name; H2 has different generated names and metadata casing. */
public final class V011__private_assets extends BaseJavaMigration {
    private String source() {
        try {return new ClassPathResource("database/V011__private_assets.sql").getContentAsString(StandardCharsets.UTF_8);}
        catch(IOException failure) {throw new IllegalStateException("Private asset migration is unavailable",failure);}
    }
    @Override public Integer getChecksum() {return source().hashCode();}
    @Override public void migrate(Context context) throws SQLException {
        var constraints=new ArrayList<String>();
        try(var query=context.getConnection().prepareStatement("SELECT c.constraint_name FROM information_schema.check_constraints c JOIN information_schema.table_constraints t ON t.constraint_catalog=c.constraint_catalog AND t.constraint_schema=c.constraint_schema AND t.constraint_name=c.constraint_name WHERE LOWER(t.table_name)='artifact_input_reference' AND LOWER(c.check_clause) LIKE '%source_kind%'" );var result=query.executeQuery()) {
            while(result.next()) constraints.add(result.getString(1));
        }
        if(constraints.size()!=1 || !constraints.get(0).matches("[A-Za-z0-9_]+")) throw new SQLException("Expected one existing source kind CHECK before private asset migration");
        try(var statement=context.getConnection().createStatement()) {statement.execute("ALTER TABLE artifact_input_reference DROP CONSTRAINT \""+constraints.get(0)+"\"");}
        String sql=source().replaceAll("(?m)^SET NAMES .*;\\s*$","").replaceAll("(?m)^SET time_zone .*;\\s*$","")
            .replaceAll("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin","")
            .replaceAll("(?s)-- MYSQL-CHECK-REPLACEMENT-BEGIN.*?-- MYSQL-CHECK-REPLACEMENT-END","");
        ScriptUtils.executeSqlScript(context.getConnection(),new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
    }
}
