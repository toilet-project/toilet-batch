package com.geupddong.account;

import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit operator entry point. Default mode cannot write journal/checkpoint/member data. */
public final class RetirementMaintenanceCli {
    private RetirementMaintenanceCli(){}
    public static void main(String[] args) {
        try {
            String mode=args.length==0?"--dry-run":args[0];
            if(args.length>1 || !java.util.Set.of("--dry-run","--apply","--resume-reviewed").contains(mode))throw new IllegalArgumentException();
            var env=new StandardEnvironment();String url=env.getRequiredProperty("ERASURE_EVIDENCE_DB_URL");
            if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]{1,5}/toilet_db"))throw new IllegalArgumentException();
            var data=new DriverManagerDataSource(url+"?connectionTimeZone=%2B09:00&forceConnectionTimeZoneToSession=true&connectTimeout=5000&socketTimeout=15000",
                    env.getRequiredProperty("ERASURE_EVIDENCE_DB_USER"),env.getRequiredProperty("ERASURE_EVIDENCE_DB_PASSWORD"));
            var jdbc=new JdbcTemplate(data);jdbc.setQueryTimeout(15);
            boolean apply=!mode.equals("--dry-run");var result=RetirementMaintenanceRunner.run(env,jdbc,apply,mode.equals("--resume-reviewed"));
            System.out.printf("dryRun=%s %s=%d held=%d resumed=%s%n",!apply,apply?"completed":"eligible",result.completed(),result.held(),result.resumed());
        }catch(Exception ignored){System.err.println("RETIREMENT_MAINTENANCE_HELD: inspect approved evidence and independent state");System.exit(2);}
    }
}
