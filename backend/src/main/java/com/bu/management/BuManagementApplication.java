package com.bu.management;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * BU Management System Application
 * 电商BU内部管理系统
 *
 * @author BU Team
 * @since 2026-04-02
 */
@SpringBootApplication
@MapperScan("com.bu.management.mapper")
@EnableScheduling
public class BuManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuManagementApplication.class, args);
        System.out.println("""

                ====================================
                BU Management System Started!
                API Docs: http://localhost:8081/doc.html
                ====================================
                """);
    }
}
