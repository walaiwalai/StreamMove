//package com.sh.schedule.config;
//
//import org.quartz.Scheduler;
//import org.quartz.SchedulerException;
//import org.quartz.impl.StdSchedulerFactory;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//
//
//@Configuration
//public class SchedulerConfig {
//    @Bean(name = "stdSchedulerFactory")
//    public StdSchedulerFactory stdSchedulerFactory() {
//        StdSchedulerFactory stdSchedulerFactory = new StdSchedulerFactory();
//        return stdSchedulerFactory;
//    }
//
//    @Bean(name = "scheduler")
//    public Scheduler scheduler(StdSchedulerFactory stdSchedulerFactory) throws SchedulerException {
//        return stdSchedulerFactory.getScheduler();
//    }
//}
