package com.flipped.spaceservice.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消息队列基础设施（阶段 5d，消费侧）
 *
 * <p><b>拓扑必须与生产端（单体）完全一致</b>，否则消息发不到这里、或者声明冲突：
 * <pre>
 *   picture.mq       topic  业务事件（与单体同一个交换机名）
 *   picture.mq.dlx   direct 死信交换机
 *   picture.mq.retry direct 延迟重试交换机（TTL 到期后死信回业务交换机）
 * </pre>
 *
 * <p>⚠️ <b>交换机/队列的声明参数改动会让消费者被静默中止</b>（实测踩过）：
 * durable 队列一旦用某个 {@code x-message-ttl} 建成，之后改这个参数再启动，
 * broker 会报 {@code PRECONDITION_FAILED} 并中止整个监听容器的连接 ——
 * 表现是「队列里有消息、consumers=0、谁也不消费」，而应用看起来一切正常。
 * 改这类参数时要先删队列让它重建。
 */
@Configuration
@ConditionalOnProperty(name = "picture.mq.enabled", havingValue = "true")
@Slf4j
public class MqConfig {

    /**
     * 业务主交换机（与单体同一个，是同一个 broker 上的同一份拓扑）
     */
    public static final String EXCHANGE = "picture.mq";
    public static final String DLX_EXCHANGE = "picture.mq.dlx";
    public static final String RETRY_EXCHANGE = "picture.mq.retry";

    /**
     * 5d-a 自检队列：只打日志，用来先把「消费地基」单独验证通
     */
    public static final String TEST_QUEUE = "picture.mq.space.test";
    public static final String TEST_ROUTING_KEY = "picture.space.test";
    public static final String TEST_DLQ = "picture.mq.space.test.dlq";
    public static final String TEST_RETRY_QUEUE = "picture.mq.space.test.retry";

    /**
     * 5d-b 配额队列：单体发 {@code quota.changed}，本服务幂等消费
     */
    public static final String QUOTA_QUEUE = "picture.mq.space.quota";
    public static final String QUOTA_ROUTING_KEY = "quota.changed";
    public static final String QUOTA_DLQ = "picture.mq.space.quota.dlq";
    public static final String QUOTA_RETRY_QUEUE = "picture.mq.space.quota.retry";

    @Value("${picture.mq.retry-delay-ms:5000}")
    private int retryDelayMs;

    // ==================== 交换机 ====================
    // 单独声明而不假设「生产端一定先起」：两个服务谁先启动都能把拓扑建好，
    // 参数一致时重复声明是幂等的（不一致才会 PRECONDITION_FAILED，那正是我们想暴露的）

    @Bean
    public TopicExchange pictureExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange pictureDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange pictureRetryExchange() {
        return new DirectExchange(RETRY_EXCHANGE, true, false);
    }

    // ==================== 5d-a 自检队列 ====================

    @Bean
    public Queue spaceTestQueue() {
        return QueueBuilder.durable(TEST_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(TEST_DLQ)
                .build();
    }

    @Bean
    public Binding spaceTestBinding(Queue spaceTestQueue, TopicExchange pictureExchange) {
        return BindingBuilder.bind(spaceTestQueue).to(pictureExchange).with(TEST_ROUTING_KEY);
    }

    @Bean
    public Queue spaceTestDlq() {
        return QueueBuilder.durable(TEST_DLQ).build();
    }

    @Bean
    public Binding spaceTestDlqBinding(Queue spaceTestDlq, DirectExchange pictureDlxExchange) {
        return BindingBuilder.bind(spaceTestDlq).to(pictureDlxExchange).with(TEST_DLQ);
    }

    @Bean
    public Queue spaceTestRetryQueue() {
        return QueueBuilder.durable(TEST_RETRY_QUEUE)
                .ttl(retryDelayMs)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(TEST_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding spaceTestRetryBinding(Queue spaceTestRetryQueue, DirectExchange pictureRetryExchange) {
        return BindingBuilder.bind(spaceTestRetryQueue).to(pictureRetryExchange).with(TEST_RETRY_QUEUE);
    }

    // ==================== 5d-b 配额队列 ====================

    @Bean
    public Queue quotaQueue() {
        return QueueBuilder.durable(QUOTA_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(QUOTA_DLQ)
                .build();
    }

    @Bean
    public Binding quotaBinding(Queue quotaQueue, TopicExchange pictureExchange) {
        return BindingBuilder.bind(quotaQueue).to(pictureExchange).with(QUOTA_ROUTING_KEY);
    }

    @Bean
    public Queue quotaDlq() {
        return QueueBuilder.durable(QUOTA_DLQ).build();
    }

    @Bean
    public Binding quotaDlqBinding(Queue quotaDlq, DirectExchange pictureDlxExchange) {
        return BindingBuilder.bind(quotaDlq).to(pictureDlxExchange).with(QUOTA_DLQ);
    }

    @Bean
    public Queue quotaRetryQueue() {
        return QueueBuilder.durable(QUOTA_RETRY_QUEUE)
                .ttl(retryDelayMs)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(QUOTA_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding quotaRetryBinding(Queue quotaRetryQueue, DirectExchange pictureRetryExchange) {
        return BindingBuilder.bind(quotaRetryQueue).to(pictureRetryExchange).with(QUOTA_RETRY_QUEUE);
    }

    // ==================== 消息转换 ====================

    /**
     * JSON 而不是 JDK 序列化：必须与生产端一致，否则消息体解析不出来。
     * （JDK 序列化还要求两端类路径完全一致，跨服务不可接受。）
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}
