package com.qiankun.bootstrap_register_init;

import org.springframework.boot.BootstrapRegistry;
import org.springframework.boot.BootstrapRegistryInitializer;
import org.springframework.stereotype.Component;

/**
 * @Description:
 * @Date : 2024/01/16 10:32
 * @Auther : tiankun
 */
public class MyBootstrapRegistryInitializer implements BootstrapRegistryInitializer {
    @Override
    public void initialize(BootstrapRegistry registry) {
        System.out.println("1111");
        // registry.register();
    }
}
