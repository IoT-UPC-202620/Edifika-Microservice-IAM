package com.edifika.iam.authentication.infrastructure.messaging;

import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.StatelessRetryOperationsInterceptor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class RabbitMqConfigurationTest {

    private final RabbitMqConfiguration configuration = new RabbitMqConfiguration();

    @Test
    @DisplayName("el interceptor está en la advice chain del factory")
    void factoryUsesRetryInterceptor() {
        var interceptor = configuration.amqpRetryOperationsInterceptor();
        var connectionFactory = Mockito.mock(ConnectionFactory.class);
        var factory = configuration.rabbitListenerContainerFactory(
                connectionFactory, new Jackson2JsonMessageConverter(), interceptor);
        var adviceChain = factory.getAdviceChain();
        assertNotNull(adviceChain);
        assertEquals(1, adviceChain.length);
        assertSame(interceptor, adviceChain[0]);
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (var cause = throwable; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("fallo transitorio: se procesa en el reintento (2 ejecuciones, sin excepción)")
    void transientFailureIsRetriedAndSucceeds() throws Throwable {
        var interceptor = configuration.amqpRetryOperationsInterceptor();
        var attempts = new AtomicInteger();
        var invocation = Mockito.mock(MethodInvocation.class);
        when(invocation.proceed()).thenAnswer(inv -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("fallo transitorio");
            }
            return "ok";
        });

        assertEquals("ok", interceptor.invoke(invocation));
        assertEquals(2, attempts.get());
    }

    @Test
    @DisplayName("fallo permanente: 3 ejecuciones en total; la cadena de causas lleva AmqpRejectAndDontRequeueException (→ DLQ)")
    void permanentFailureExhaustsRetries() throws Throwable {
        var interceptor = configuration.amqpRetryOperationsInterceptor();
        var attempts = new AtomicInteger();
        var invocation = Mockito.mock(MethodInvocation.class);
        when(invocation.proceed()).thenAnswer(inv -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("fallo permanente");
        });
        when(invocation.getArguments()).thenReturn(new Object[]{new Object(),
                new Message("body".getBytes(), new MessageProperties())});

        // AMQP 4: el recoverer lanza AmqpRejectAndDontRequeueException, envuelta en
        // ListenerExecutionFailedException ("Retry Policy Exhausted"). El contenedor
        // reconoce esa cadena y rechaza sin requeue → DLX → DLQ.
        var thrown = assertThrows(ListenerExecutionFailedException.class, () -> interceptor.invoke(invocation));
        assertEquals(3, attempts.get());
        assertTrue(hasCause(thrown, AmqpRejectAndDontRequeueException.class));
    }
}
