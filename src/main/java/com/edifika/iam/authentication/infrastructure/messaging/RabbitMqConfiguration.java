package com.edifika.iam.authentication.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología del lado IAM:
 * - Exchange topic compartido `edifika.events` (durable).
 * - Cola propia `iam.resident-provisioning` ligada a `residential.resident.assigned`,
 *   con DLX `edifika.dlx` y DLQ `iam.resident-provisioning.dlq`.
 * La cola `residential.resident-link-completion` la declara Residential.
 */
@Configuration
public class RabbitMqConfiguration {

    public static final String EVENTS_EXCHANGE = "edifika.events";
    public static final String DLX_EXCHANGE = "edifika.dlx";
    public static final String RESIDENT_ASSIGNED_ROUTING_KEY = "residential.resident.assigned";
    public static final String USER_CREATED_ROUTING_KEY = "iam.resident.user-created";
    public static final String PROVISIONING_QUEUE = "iam.resident-provisioning";
    public static final String PROVISIONING_DLQ = PROVISIONING_QUEUE + ".dlq";

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange dlxExchange() {
        return new TopicExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue residentProvisioningQueue() {
        return QueueBuilder.durable(PROVISIONING_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", PROVISIONING_DLQ)
                .build();
    }

    @Bean
    public Queue residentProvisioningDlq() {
        return QueueBuilder.durable(PROVISIONING_DLQ).build();
    }

    @Bean
    public Binding residentProvisioningBinding() {
        return BindingBuilder.bind(residentProvisioningQueue())
                .to(eventsExchange())
                .with(RESIDENT_ASSIGNED_ROUTING_KEY);
    }

    @Bean
    public Binding residentProvisioningDlqBinding() {
        return BindingBuilder.bind(residentProvisioningDlq())
                .to(dlxExchange())
                .with(PROVISIONING_DLQ);
    }

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter converter) {
        var template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        return factory;
    }
}
