package com.example.toiletbatch.account;

import com.geupddong.account.ErasureLedger;
import com.geupddong.account.ErasureLedgerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class ErasureLedgerConfiguration {
    @Bean(destroyMethod = "close")
    public ErasureLedger erasureLedger(Environment env, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return com.geupddong.account.ProtectedErasureLedgerFactory.create(env, jdbc);
    }
}
