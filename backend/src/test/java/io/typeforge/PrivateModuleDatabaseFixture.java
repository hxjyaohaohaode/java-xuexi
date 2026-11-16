package io.typeforge;

import io.typeforge.shared.JsonCodec;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Explicit opt-in only; never infer a test database from a general developer or production connection. */
public final class PrivateModuleDatabaseFixture {
    private static final boolean MYSQL="true".equals(System.getenv("TYPEFORGE_MYSQL_ACCOUNT_TEST")) || "true".equals(System.getenv("TYPEFORGE_MYSQL_V06_TEST"));
    public static void configure(DynamicPropertyRegistry properties,String h2Name) {
        if(!MYSQL) {
            properties.add("spring.datasource.url",()->"jdbc:h2:mem:"+h2Name+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH");
            properties.add("spring.datasource.username",()->"sa");properties.add("spring.datasource.password",()->"");
            properties.add("spring.datasource.driver-class-name",()->"org.h2.Driver");
            properties.add("spring.flyway.locations",()->"classpath:io/typeforge/operations/migrations/h2");return;
        }
        String url=required("TYPEFORGE_DB_URL");if(!url.startsWith("jdbc:mysql://"))throw new IllegalStateException("Private module verification requires explicit MySQL");
        URI target=URI.create(url.substring(5));
        if(!Set.of("127.0.0.1","localhost","::1","[::1]").contains(target.getHost()) || target.getUserInfo()!=null || target.getPort()<1 || target.getPort()>65535 || !target.getPath().matches("/typeforge_(runtime|smoke_[a-z0-9_]{1,48})")) throw new IllegalStateException("Private module verification is restricted to a named loopback test database");
        String username=required("TYPEFORGE_DB_USER"),password=required("TYPEFORGE_DB_PASSWORD");
        properties.add("spring.datasource.url",()->url);properties.add("spring.datasource.username",()->username);properties.add("spring.datasource.password",()->password);
        properties.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");properties.add("spring.flyway.locations",()->"classpath:database");
    }
    public static void prove(JdbcTemplate jdbc,JsonCodec json,String suite) {
        if(!MYSQL)return;
        Map<String,Object> proof=jdbc.execute((ConnectionCallback<Map<String,Object>>)connection->Map.of("suite",suite,"engine",connection.getMetaData().getDatabaseProductName(),"version",connection.getMetaData().getDatabaseProductVersion(),"database",connection.getCatalog()));
        if(!"MySQL".equals(proof.get("engine")) || !proof.get("version").toString().startsWith("8.4."))throw new IllegalStateException("Actual private module JDBC connection is not the required MySQL 8.4 engine");
        System.out.println("TYPEFORGE_PRIVATE_MODULE_EVIDENCE "+json.write(proof));
    }
    private static String required(String key) {String value=System.getenv(key);if(value==null || value.isBlank())throw new IllegalStateException("Missing private module test setting: "+key);return value;}
    private PrivateModuleDatabaseFixture() {}
}
