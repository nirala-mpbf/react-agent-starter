package com.meg.agentsdk.config;

import com.meg.agentsdk.annotation.ReactAgentRouter;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;
import com.meg.agentsdk.core.Agent;

import java.util.Arrays;
import java.util.List;

/**
 * Converts annotation metadata into a Spring FactoryBean definition.
 */
public class ReactAgentRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {

        try {

            Class<?> type = ClassUtils.forName(metadata.getClassName(), ReactAgentRegistrar.class.getClassLoader());
            if (Agent.class.isAssignableFrom(type)) {
                return;
            }
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("Cannot load annotated agent", error);
        }

        ReactAgentRouter reactAgentRouterDetails = metadata.getAnnotations().get(ReactAgentRouter.class).synthesize();

        String name = environment.resolveRequiredPlaceholders(reactAgentRouterDetails.name()).trim();

        if (name.isEmpty()) {
            throw new IllegalArgumentException("Agent name must not be blank");
        }

        String beanName = name + "Builder";
        if (registry.containsBeanDefinition(beanName)) {
            throw new IllegalStateException("Agent builder bean already registered: " + beanName);
        }

        RootBeanDefinition beanDefinition = new RootBeanDefinition(AgentBuilderFactoryBean.class);
        beanDefinition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);

        ConstructorArgumentValues arguments = beanDefinition.getConstructorArgumentValues();
        arguments.addIndexedArgumentValue(0, name);
        arguments.addIndexedArgumentValue(1, reactAgentRouterDetails.model());
        arguments.addIndexedArgumentValue(2, Arrays.stream(reactAgentRouterDetails.mcp())
                                                .map(server -> new AgentBuilderFactoryBean.Endpoint(server.name(), server.url()))
                                                .toList());
        arguments.addIndexedArgumentValue(3, List.of(reactAgentRouterDetails.allowedTools()));

        beanDefinition.getPropertyValues().add("workflow", reactAgentRouterDetails.workflow());
        beanDefinition.getPropertyValues().add("prompt", reactAgentRouterDetails.prompt());
        registry.registerBeanDefinition(beanName, beanDefinition);
    }

}


