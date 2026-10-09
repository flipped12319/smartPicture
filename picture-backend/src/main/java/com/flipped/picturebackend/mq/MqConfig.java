package com.flipped.picturebackend.mq;

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
 * 消息队列基础设施（阶段 5a）
 *
 * <p><b>为什么整体挂在 {@code picture.mq.enabled} 开关下</b>：
 * 本项目所有外部依赖都遵守同一条底线 —— 「默认不依赖它也能开发」
 * （Nacos 靠 {@code optional:} + {@code fail-fast:false}，Redis 靠熔断降级）。
 * RabbitMQ 也不例外：默认 {@code false} 时本配置类不生效，
 * 应用既不会去连 broker、也不会声明队列，索引回落为本地线程池执行（与阶段 4 行为一致）。
 * 需要验证 MQ 时把它打开即可。
 *
 * <p><b>拓扑</b>（刻意分成三个交换机，职责不同，不要图省事合成一个）：
 * <pre>
 *   picture.mq                topic  业务事件（索引、缓存广播…），routingKey 形如 picture.index.requested
 *   picture.mq.dlx            direct 死信交换机：被拒绝/过期的消息都进这里
 *   picture.mq.retry          direct 延迟重试用：TTL 到期后死信回业务交换机
 * </pre>
 *
 * <p><b>为什么用「延迟队列」而不是 {@code spring-rabbit} 的 retry 拦截器</b>：
 * 拦截器重试是在**消费线程里 sleep**，长耗时任务会把线程占住（这与阶段 5 要解决的
 * 「180 秒任务占线程池」是同一个毛病）。延迟队列把等待挪出线程：消息带 TTL 进
 * retry 队列，过期后由 broker 死信回业务交换机，**消费者线程立刻释放**。
 */
@Configuration
@ConditionalOnProperty(name = "picture.mq.enabled", havingValue = "true")
@Slf4j
public class MqConfig {

    /**
     * 业务主交换机（topic）
     */
    public static final String EXCHANGE = "picture.mq";
    /**
     * 死信交换机（direct）
     */
    public static final String DLX_EXCHANGE = "picture.mq.dlx";
    /**
     * 延迟重试交换机（direct）
     */
    public static final String RETRY_EXCHANGE = "picture.mq.retry";

    /**
     * 5a 的自检队列：只打日志、不做业务。用它把「publish / consume / 幂等去重 / DLQ」
     * 这套地基先单独验证通，再让业务依赖它。
     */
    public static final String TEST_QUEUE = "picture.mq.test";
    public static final String TEST_ROUTING_KEY = "picture.test.requested";
    public static final String TEST_DLQ = "picture.mq.test.dlq";
    public static final String TEST_RETRY_QUEUE = "picture.mq.test.retry";

    /**
     * 5b：图片向量索引队列（§4.2）
     * <p>
     * 为什么值得单独一条队列：生成索引要调多模态模型，**单张可能几十秒**，
     * 而原来的做法是把它丢进本机线程池（core 2 / max 4 / queue 200）——
     * 批量 30 张就塞满，之后所有索引请求一起排队，且进程重启即丢。
     * 进队列后：削峰、可重试、可多实例并行消费（加消费者即扩容）。
     */
    public static final String INDEX_QUEUE = "picture.mq.index";
    public static final String INDEX_ROUTING_KEY = "picture.index.requested";
    public static final String INDEX_DLQ = "picture.mq.index.dlq";
    public static final String INDEX_RETRY_QUEUE = "picture.mq.index.retry";

    /**
     * 5d-b：额度变更（§4.6 配额最终一致）
     * <p>
     * 生产端是**单体**（删除图片时还额度），消费端是 **space-service**（它才是 space 表的属主）。
     * 所以这条队列由 space-service 声明与消费；单体只负责发。
     */
    public static final String QUOTA_ROUTING_KEY = "quota.changed";

    /**
     * 延迟重试的等待时长（毫秒）。5a 用一个较短的值方便观察；
     * 生产可按「重试次数递增」再做多级延迟队列。
     */
    @Value("${picture.mq.retry-delay-ms:5000}")
    private int retryDelayMs;

    // ==================== 交换机 ====================

    @Bean
    public TopicExchange pictureExchange() {
        // durable=true：broker 重启后交换机还在（队列同理），否则重启即丢声明
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

    // ==================== 5a 自检队列 ====================

    /**
     * 业务队列：消费失败时进死信交换机。
     * <p>
     * 注意这里**没有配 {@code x-message-ttl}** —— 业务队列不要设 TTL，
     * 否则正常排队等待的消息也会被当过期消息丢进 DLQ。
     */
    @Bean
    public Queue mqTestQueue() {
        return QueueBuilder.durable(TEST_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(TEST_DLQ)
                .build();
    }

    @Bean
    public Binding mqTestBinding(Queue mqTestQueue, TopicExchange pictureExchange) {
        return BindingBuilder.bind(mqTestQueue).to(pictureExchange).with(TEST_ROUTING_KEY);
    }

    /**
     * 死信队列：人工查看 / 重放的入口。
     */
    @Bean
    public Queue mqTestDlq() {
        return QueueBuilder.durable(TEST_DLQ).build();
    }

    @Bean
    public Binding mqTestDlqBinding(Queue mqTestDlq, DirectExchange pictureDlxExchange) {
        return BindingBuilder.bind(mqTestDlq).to(pictureDlxExchange).with(TEST_DLQ);
    }

    /**
     * 延迟重试队列：消息在这里挂 {@link #retryDelayMs} 毫秒，
     * 过期后由 broker 死信回**业务交换机**，于是又回到业务队列被重新消费。
     */
    @Bean
    public Queue mqTestRetryQueue() {
        return QueueBuilder.durable(TEST_RETRY_QUEUE)
                .ttl(retryDelayMs)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(TEST_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding mqTestRetryBinding(Queue mqTestRetryQueue, DirectExchange pictureRetryExchange) {
        return BindingBuilder.bind(mqTestRetryQueue).to(pictureRetryExchange).with(TEST_RETRY_QUEUE);
    }

    // ==================== 5b：索引队列 ====================

    @Bean
    public Queue indexQueue() {
        return QueueBuilder.durable(INDEX_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(INDEX_DLQ)
                .build();
    }

    @Bean
    public Binding indexBinding(Queue indexQueue, TopicExchange pictureExchange) {
        return BindingBuilder.bind(indexQueue).to(pictureExchange).with(INDEX_ROUTING_KEY);
    }

    /**
     * 索引死信队列：模型服务长时间不可用时，失败消息堆在这里等重放，
     * 不影响后续正常图片的索引。
     */
    @Bean
    public Queue indexDlq() {
        return QueueBuilder.durable(INDEX_DLQ).build();
    }

    @Bean
    public Binding indexDlqBinding(Queue indexDlq, DirectExchange pictureDlxExchange) {
        return BindingBuilder.bind(indexDlq).to(pictureDlxExchange).with(INDEX_DLQ);
    }

    @Bean
    public Queue indexRetryQueue() {
        return QueueBuilder.durable(INDEX_RETRY_QUEUE)
                .ttl(retryDelayMs)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(INDEX_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding indexRetryBinding(Queue indexRetryQueue, DirectExchange pictureRetryExchange) {
        return BindingBuilder.bind(indexRetryQueue).to(pictureRetryExchange).with(INDEX_RETRY_QUEUE);
    }

    // ==================== 消息转换与发送 ====================

    /**
     * 用 JSON 而不是 JDK 序列化。
     * <p>
     * 必须显式声明：Spring AMQP 默认是 {@code SimpleMessageConverter}（JDK 序列化），
     * 那种二进制在管理台里完全看不懂，且要求两端类路径完全一致 ——
     * 对「Java 生产、未来可能别的语言消费」的架构是不可接受的。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 发布确认：消息真正落到 broker 才回调 ack，否则 nack。
     * <p>
     * 只打日志、**不在这里重发** —— 重发属于「本地消息表 + 定时补投」的职责（5c），
     * 在这里补发会丢掉「重试次数」和退避控制，反而更难排查。
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setMandatory(true);
        template.setReturnsCallback(returned -> log.error(
                "消息无法路由到任何队列（检查交换机/路由键），exchange = {}，routingKey = {}，replyText = {}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("消息未被 broker 确认，correlationData = {}，原因 = {}",
                        correlationData == null ? null : correlationData.getId(), cause);
            }
        });
        return template;
    }
}
