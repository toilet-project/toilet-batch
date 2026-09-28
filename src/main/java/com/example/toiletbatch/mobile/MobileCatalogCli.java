package com.example.toiletbatch.mobile;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.ArrayList;

/** One-shot publication inside the batch container, without booting Spring, HTTP, or other jobs. */
public final class MobileCatalogCli {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--check-runtime")) {
            System.out.println("mobile-catalog-cli-ready"); return;
        }
        boolean register = false;
        var retired = new ArrayList<String>();
        for (int i=0; i<args.length; i++) {
            if (args[i].equals("--register-baseline")) register=true;
            else if (args[i].equals("--retire-baseline") && i+1<args.length) retired.add(args[++i]);
            else throw new IllegalArgumentException("Unsupported publication argument");
        }
        var env = System.getenv();
        if (!"true".equals(env.get("MOBILE_CATALOG_ENABLED"))) throw new IllegalStateException("Mobile catalog is not enabled");
        var dataSource = new DriverManagerDataSource(required("SPRING_DB_URL"), required("SPRING_DB_USERNAME"), required("SPRING_DB_PASSWORD"));
        var properties = new MobileCatalogProperties(true, env.get("MOBILE_CATALOG_ORIGIN"), required("MOBILE_CATALOG_PUBLISH_TOKEN"),
                env.get("MOBILE_CATALOG_PYTHON"), env.get("MOBILE_CATALOG_SCRIPT"), env.get("MOBILE_CATALOG_WORK_DIRECTORY"), 1200);
        System.out.println(new MobileCatalogPublisher(dataSource, properties).publish(register, retired));
    }
    private static String required(String name) {
        String value=System.getenv(name);
        if (value==null || value.isBlank()) throw new IllegalStateException("Missing configuration: " + name);
        return value;
    }
}
