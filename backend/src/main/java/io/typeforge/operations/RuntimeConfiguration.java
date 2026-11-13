package io.typeforge.operations;

import java.time.Clock;
import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableScheduling
public class RuntimeConfiguration {
    @Bean Jackson2ObjectMapperBuilderCustomizer boundedJson() {
        return builder->builder.postConfigurer(mapper->mapper.getFactory().setStreamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(262144).maxNumberLength(64).build()));
    }
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean TransactionTemplate transactions(PlatformTransactionManager manager) {
        var transactions=new TransactionTemplate(manager);
        // Owner/workspace locks serialize admission. After waiting for one, reads must see
        // the previous holder's commit rather than a MySQL REPEATABLE READ snapshot
        // established by an earlier resource-ownership lookup in this transaction.
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transactions;
    }
}
