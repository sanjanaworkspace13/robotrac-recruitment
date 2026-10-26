package com.robotrac.recruitment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class RobotracRecruitmentApplication {

    public static void main(String[] args) {
        // All server-side time handling is UTC; browsers convert to local time for display.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(RobotracRecruitmentApplication.class, args);
    }
}
