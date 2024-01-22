package com.qiankun;

import com.qiankun.bootstrap_register_init.MyBootstrapRegistryInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @Description:
 * @Date : 2024/01/11 14:06
 * @Auther : tiankun
 */
@SpringBootApplication
public class MyApplication {

    public static void main(String[] args) {
        SpringApplication springApplication = new SpringApplication(MyApplication.class);
        springApplication.addBootstrapRegistryInitializer(new MyBootstrapRegistryInitializer());
        springApplication.run(args);
    }

}
