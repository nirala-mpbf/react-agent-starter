package com.meg.agentsdk.annotation;

import java.lang.annotation.*;


@Documented
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface McpServer {
    String name();
    String url();
}

