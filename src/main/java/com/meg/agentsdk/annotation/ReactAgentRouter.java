package com.meg.agentsdk.annotation;

import com.meg.agentsdk.config.MemoryConfiguration;
import com.meg.agentsdk.prompt.PromptConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

@org.springframework.stereotype.Component
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({PromptConfiguration.class, MemoryConfiguration.class})
public @interface ReactAgentRouter {

    String name() default "reactAgent";

    String model() default "";

    McpServer[] mcp() default {};

    String[] allowedTools() default {};

    String workflow() default "";

    String prompt() default "";
}



