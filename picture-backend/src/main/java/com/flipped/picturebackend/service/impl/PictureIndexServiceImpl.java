package com.flipped.picturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flipped.picturebackend.exception.BusinessException;
import com.flipped.picturebackend.exception.ErrorCode;
import com.flipped.picturebackend.exception.ThrowUtils;
import com.flipped.picturebackend.mapper.PictureMapper;
import com.flipped.picturebackend.model.entity.LocalMessage;
import com.flipped.picturebackend.mq.MqConfig;
import com.flipped.picturebackend.mq.MessageRelay;
import com.flipped.picturebackend.mq.MqMessage;
import com.flipped.picturebackend.mq.PictureIndexPayload;
import com.flipped.picturebackend.model.dto.ai.PictureIndexRequest;
import com.flipped.picturebackend.model.dto.ai.PictureIndexResponse;
import com.flipped.picturebackend.model.dto.ai.PictureSearchRequest;
import com.flipped.picturebackend.model.dto.ai.PictureSearchResponse;
import com.flipped.picturebackend.model.entity.Picture;
import com.flipped.picturebackend.model.enums.PictureIndexStatusEnum;
import com.flipped.picturebackend.model.enums.PictureReviewStatusEnum;
import com.flipped.picturebackend.service.PictureIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 图片向量索引服务实现类
 * <p>
 * 只依赖 PictureMapper（而不是 IPictureService），避免与 PictureServiceImpl 形成循环依赖。
 * <p>
 * 阶段 2：加 {@code @RefreshScope}，使下面几个从 Nacos 配置中心读来的参数
 * （{@code pictureIndex.api.base-url}、{@code pictureIndex.search.top-k}、
 * {@code pictureIndex.search.min-similarity}）在 Nacos 里改完就能生效，
 * 不需要重启 —— 否则 {@code @Value} 只在启动时绑定一次，配置中心就白接了。
 */
@Service
@Slf4j
@RefreshScope
public class PictureIndexServiceImpl implements PictureIndexService {

    /**
     * Python 索引服务的基础地址
     */
    @Value("${pictureIndex.api.base-url:http://127.0.0.1:8001}")
    private String indexApiBaseUrl;

    /**
     * 语义召回数量：远大于一页，给后续的权限过滤留出余量
     */
    @Value("${pictureIndex.search.top-k:100}")
    private int searchTopK;

    /**
     * 最小相似度（0~1），低于它的召回直接丢弃；传 0 表示不过滤
     * <p>
     * 默认 0.48 是拿当前图库的真实数据量出来的（bge-small-zh 归一化向量 + Chroma L2 距离换算）：
     * 相关查询的 top1 落在 0.51~0.61，噪声普遍在 0.40~0.47，断层很清晰。
     * 若默认 0（不过滤），语义检索会退化成「返回最近的 topK 张」——
     * 图库比 topK 小时等于把整个图库都返回回去，搜索结果看起来完全不相关。
     * <p>
     * 取 0.48 既能过滤掉「量子物理」这类完全无关的查询，又能保住「赛车 → 跑车」这种近似召回。
     * 过滤后一条都不剩时，applySemanticSearch 不会设置 semanticIds，
     * 调用方会继续走关键词 LIKE 兜底，因此不会出现「什么都搜不到」的死路。
     * ⚠️ 更换嵌入模型后必须重新测量，不同模型的分数区间完全不同。
     */
    @Value("${pictureIndex.search.min-similarity:0.48}")
    private double searchMinSimilarity;

    private final RestTemplate restTemplate;

    /**
     * 消息来源标识（排查时用来区分「谁发的这条索引请求」）
     */
    private static final String SOURCE = "picture-backend";

    /**
     * 检索专用客户端：超时设置得很短
     * <p>
     * 检索是高频同步路径（每次带搜索词的列表查询都会调用），必须快速失败并降级为关键词搜索；
     * 而「生成索引」要调多模态模型、单张可能几十秒。两者超时需求差两个数量级，
     * 共用一个 RestTemplate 会让 8001 一慢就把 Tomcat 线程成片拖住。
     */
    private final RestTemplate searchRestTemplate;

    @Resource
    private PictureMapper pictureMapper;

    /**
     * 图片索引专用线程池，用于把重建索引这类批量任务放到后台执行
     */
    @Resource(name = "pictureIndexExecutor")
    private Executor pictureIndexExecutor;

    public PictureIndexServiceImpl() {
        // 生成索引：要调用多模态模型生成标签，单张可能耗时数十秒
        SimpleClientHttpRequestFactory indexFactory = new SimpleClientHttpRequestFactory();
        indexFactory.setConnectTimeout(5000);
        indexFactory.setReadTimeout(180000);
        this.restTemplate = new RestTemplate(indexFactory);

        // 检索：高频路径，宁可降级为关键词搜索也不能拖住请求线程
        SimpleClientHttpRequestFactory searchFactory = new SimpleClientHttpRequestFactory();
        searchFactory.setConnectTimeout(1000);
        searchFactory.setReadTimeout(2000);
        this.searchRestTemplate = new RestTemplate(searchFactory);
    }

    /**
     * 回填字段的长度上限，防止模型返回超长内容导致入库失败
     */
    private static final int MAX_NAME_LENGTH = 32;

    private static final int MAX_CATEGORY_LENGTH = 16;

    private static final int MAX_INTRODUCTION_LENGTH = 300;

    /**
     * 索引消息的可靠投递器（阶段 5c）。
     * <p>
     * 与 {@link #mqPublisherProvider} 一样用 ObjectProvider：MQ 关闭时这些 bean 不存在，
     * 直接注入会让应用起不来。
     */
    @Resource
    private ObjectProvider<MessageRelay> messageRelayProvider;

    /**
     * 投递索引请求：MQ 开着就**先落本地消息表再投递**，否则回落到本机线程池。
     *
     * <p><b>为什么从 5b 的「直接发」改成 5c 的「先落库再发」</b>：
     * 直接发的窗口是「业务提交成功 → 消息发出」之间的进程崩溃/MQ 抖动，那一瞬间消息就没了，
     * 而索引失败是**静默**的（用户不知道，图也搜不到）。落库后即使当时没发出去，
     * 定时任务也会补投，消息不会丢。
     *
     * @return true 表示已交给 MQ 这条链路（调用方不要再用线程池跑一遍）
     */
    private boolean publishIndexRequest(Long pictureId, boolean fillBlankFields) {
        if (pictureId == null || pictureId <= 0) {
            return false;
        }
        MessageRelay relay = messageRelayProvider.getIfAvailable();
        if (relay == null) {
            return false;
        }
        PictureIndexPayload payload = new PictureIndexPayload(pictureId, fillBlankFields);
        MqMessage<PictureIndexPayload> message = MqMessage.of(MqConfig.INDEX_ROUTING_KEY, SOURCE, payload);
        // 1) 落本地消息表（若在事务内，与业务数据一起原子提交）
        LocalMessage record = relay.saveInTransaction(MqConfig.EXCHANGE, MqConfig.INDEX_ROUTING_KEY, message);
        // 2) 立即尝试投递；失败不抛，定时任务会补投
        if (!relay.publishNow(record)) {
            log.warn("索引消息立即投递未成功，已留在本地消息表等待补投：pictureId = {}，messageId = {}",
                    pictureId, record.getMessageId());
        }
        return true;
    }

    @Async("pictureIndexExecutor")
    @Override
    public void indexPictureAsync(Long pictureId) {
        if (publishIndexRequest(pictureId, false)) {
            return;
        }
        doIndex(pictureId, false);
    }

    @Async("pictureIndexExecutor")
    @Override
    public void indexPictureAndFillAsync(Long pictureId) {
        if (publishIndexRequest(pictureId, true)) {
            return;
        }
        doIndex(pictureId, true);
    }

    /**
     * 同步建索引，失败**抛异常**（给 MQ 消费者用，见接口注释）
     */
    @Override
    public void indexPictureNow(Long pictureId, boolean fillBlankFields) {
        doIndex(pictureId, fillBlankFields, true);
    }

    /**
     * 索引主体逻辑
     *
     * @param fillBlankFields 是否把模型生成的内容回填到图片表（仅审核通过时为 true）
     */
    private void doIndex(Long pictureId, boolean fillBlankFields) {
        // 老的「尽力而为」语义：失败只记状态，不打扰调用方（本机线程池那条路走这里）
        doIndex(pictureId, fillBlankFields, false);
    }

    /**
     * 索引主体逻辑
     *
     * @param fillBlankFields 是否把模型生成的内容回填到图片表（仅审核通过时为 true）
     * @param throwOnFailure  失败时是否抛异常。
     *                        MQ 消费者传 true（让它能 reject 并重试），
     *                        本机线程池传 false（同步丢在后台线程里，抛了也没人接）
     */
    private void doIndex(Long pictureId, boolean fillBlankFields, boolean throwOnFailure) {
        if (pictureId == null || pictureId <= 0) {
            return;
        }
        Picture picture = pictureMapper.selectById(pictureId);
        if (picture == null) {
            // 图片可能在索引前就被删除了，属于正常情况（消息可以安心 ack）
            log.info("图片索引跳过：图片不存在或已删除，id = {}", pictureId);
            return;
        }

        PictureIndexResponse response;
        try {
            response = callIndexApi(picture);
        } catch (Exception e) {
            if (throwOnFailure) {
                // 交给 MQ 那条路重试；**不在这里写 FAILED** ——
                // 消息还会被重试，此刻写「失败」只会在重试成功后留下过期状态。
                // 真正走到死信队列时的状态由 5c 的重放/对账逻辑负责。
                log.error("图片索引失败（将由 MQ 重试或进入死信），id = {}，messageId = 见消息日志", pictureId, e);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片索引失败");
            }
            log.error("图片索引失败，id = {}，可稍后重试", pictureId, e);
            updateIndexStatus(pictureId, PictureIndexStatusEnum.FAILED, null);
            return;
        }

        // 只有审核通过时才回填，且只补空字段，不覆盖用户已填内容
        Picture fillBack = fillBlankFields ? buildFillBack(picture, response) : null;
        updateIndexStatus(pictureId, PictureIndexStatusEnum.SUCCESS, fillBack);
        log.info("图片索引成功，id = {}，是否回填 = {}，生成标签 = {}，模型说明 = {}",
                pictureId, fillBack != null, response.getTags(), response.getMessage());
    }

    /**
     * 构造「只补齐空字段」的更新对象
     *
     * @return 有字段需要补时返回携带新值的对象；一个都不用补时返回 null
     */
    private Picture buildFillBack(Picture picture, PictureIndexResponse response) {
        Picture update = new Picture();
        boolean changed = false;
        if (StrUtil.isBlank(picture.getName()) && StrUtil.isNotBlank(response.getName())) {
            update.setName(StrUtil.subPre(response.getName().trim(), MAX_NAME_LENGTH));
            changed = true;
        }
        if (StrUtil.isBlank(picture.getCategory()) && StrUtil.isNotBlank(response.getCategory())) {
            update.setCategory(StrUtil.subPre(response.getCategory().trim(), MAX_CATEGORY_LENGTH));
            changed = true;
        }
        if (StrUtil.isBlank(picture.getIntroduction()) && StrUtil.isNotBlank(response.getIntroduction())) {
            update.setIntroduction(StrUtil.subPre(response.getIntroduction().trim(), MAX_INTRODUCTION_LENGTH));
            changed = true;
        }
        if (StrUtil.isBlank(picture.getTags()) && CollUtil.isNotEmpty(response.getTags())) {
            update.setTags(JSONUtil.toJsonStr(response.getTags()));
            changed = true;
        }
        return changed ? update : null;
    }

    @Async("pictureIndexExecutor")
    @Override
    public void removePictureIndexAsync(List<Long> pictureIds) {
        if (CollUtil.isEmpty(pictureIds)) {
            return;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("pictureIds", pictureIds);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.exchange(indexApiBaseUrl + "/picture/remove", HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
            log.info("已请求删除图片向量索引，数量 = {}", pictureIds.size());
        } catch (Exception e) {
            log.error("删除图片向量索引失败，pictureIds = {}", pictureIds, e);
        }
    }

    @Override
    public List<Long> searchPictureIds(String query, Long spaceId) {
        if (StrUtil.isBlank(query)) {
            return Collections.emptyList();
        }
        PictureSearchRequest request = new PictureSearchRequest();
        request.setQuery(query.trim());
        request.setTopK(searchTopK);
        // 索引时公共图库的 spaceId 存的是空串，这里必须保持一致才能过滤到
        request.setSpaceId(spaceId == null ? "" : String.valueOf(spaceId));
        if (searchMinSimilarity > 0) {
            request.setMinSimilarity(searchMinSimilarity);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<PictureSearchResponse> response = searchRestTemplate.exchange(
                    indexApiBaseUrl + "/picture/search",
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    PictureSearchResponse.class
            );
            PictureSearchResponse body = response.getBody();
            if (body == null || CollUtil.isEmpty(body.getHits())) {
                return Collections.emptyList();
            }
            return body.getHits().stream()
                    .map(PictureSearchResponse.PictureSearchHit::getPictureId)
                    .map(this::parsePictureId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // 检索服务不可用不作为错误处理：返回空集合，由调用方降级为关键词搜索
            log.error("向量检索失败，将降级为关键词搜索，query = {}", query, e);
            return Collections.emptyList();
        }
    }

    @Override
    public int rebuildIndex(boolean onlyMissing) {
        List<Long> pictureIds = listIndexCandidateIds(onlyMissing);
        if (CollUtil.isEmpty(pictureIds)) {
            log.info("重建索引：没有需要处理的图片（onlyMissing = {}）", onlyMissing);
            return 0;
        }
        log.info("重建索引：共 {} 张图片待处理（onlyMissing = {}），已提交到后台",
                pictureIds.size(), onlyMissing);
        // 提交一个总任务，再由它把每张图分发到线程池：
        // 这样 HTTP 线程既不会被长耗时任务占用，也不会因为队列满触发 CallerRunsPolicy 被拖住
        pictureIndexExecutor.execute(() -> pictureIds.forEach(
                pictureId -> pictureIndexExecutor.execute(() -> doIndex(pictureId, false))));
        return pictureIds.size();
    }

    /**
     * 查询需要重建索引的图片 id，只包含已过审的图片
     */
    private List<Long> listIndexCandidateIds(boolean onlyMissing) {
        LambdaQueryWrapper<Picture> queryWrapper = new LambdaQueryWrapper<Picture>()
                .select(Picture::getId)
                .eq(Picture::getReviewStatus, PictureReviewStatusEnum.PASS.getValue());
        if (onlyMissing) {
            // 未索引以及索引失败的都需要处理
            queryWrapper.and(wrapper -> wrapper.isNull(Picture::getIndexStatus)
                    .or()
                    .ne(Picture::getIndexStatus, PictureIndexStatusEnum.SUCCESS.getValue()));
        }
        queryWrapper.orderByAsc(Picture::getId);
        return pictureMapper.selectList(queryWrapper).stream()
                .map(Picture::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> autoFillPictureInfo(Long pictureId) {
        ThrowUtils.throwIf(pictureId == null || pictureId <= 0, ErrorCode.PARAMS_ERROR, "图片 id 不合法");
        Picture picture = pictureMapper.selectById(pictureId);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        // 四个字段都已有值时没有补充的必要，直接返回，省掉一次十几秒的模型调用
        if (isNothingToFill(picture)) {
            log.info("智能补充：名称/分类/简介/标签均已填写，跳过，id = {}", pictureId);
            return Collections.emptyList();
        }

        PictureIndexResponse response = callIndexApi(picture);
        Picture fillBack = buildFillBack(picture, response);
        if (fillBack == null) {
            log.info("智能补充：模型没有生成可用的内容，id = {}", pictureId);
            return Collections.emptyList();
        }
        updateIndexStatus(pictureId, PictureIndexStatusEnum.SUCCESS, fillBack);
        List<String> filledFields = describeFilledFields(fillBack);
        log.info("智能补充完成，id = {}，补充字段 = {}", pictureId, filledFields);
        return filledFields;
    }

    /**
     * 四个字段是否都已经有值（有值就没必要再补充了）
     */
    private boolean isNothingToFill(Picture picture) {
        return StrUtil.isNotBlank(picture.getName())
                && StrUtil.isNotBlank(picture.getCategory())
                && StrUtil.isNotBlank(picture.getIntroduction())
                && StrUtil.isNotBlank(picture.getTags());
    }

    /**
     * 列出本次被补上的字段名（中文，可直接用于前端提示）
     */
    private List<String> describeFilledFields(Picture fillBack) {
        List<String> filledFields = new ArrayList<>();
        if (StrUtil.isNotBlank(fillBack.getName())) {
            filledFields.add("名称");
        }
        if (StrUtil.isNotBlank(fillBack.getCategory())) {
            filledFields.add("分类");
        }
        if (StrUtil.isNotBlank(fillBack.getIntroduction())) {
            filledFields.add("简介");
        }
        if (StrUtil.isNotBlank(fillBack.getTags())) {
            filledFields.add("标签");
        }
        return filledFields;
    }

    /**
     * 向量库里图片 id 是字符串，这里安全地转回 Long
     */
    private Long parsePictureId(String pictureId) {
        if (StrUtil.isBlank(pictureId)) {
            return null;
        }
        try {
            return Long.parseLong(pictureId.trim());
        } catch (NumberFormatException e) {
            log.warn("向量库返回了非法的图片 id: {}", pictureId);
            return null;
        }
    }

    /**
     * 调用 Python 索引服务
     */
    private PictureIndexResponse callIndexApi(Picture picture) {
        PictureIndexRequest request = new PictureIndexRequest();
        request.setPictureId(picture.getId());
        request.setName(picture.getName());
        request.setIntroduction(picture.getIntroduction());
        request.setCategory(picture.getCategory());
        request.setUrl(picture.getUrl());
        request.setSpaceId(picture.getSpaceId());
        request.setUserId(picture.getUserId());
        request.setReviewStatus(picture.getReviewStatus());
        request.setTags(parseTags(picture.getTags()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<PictureIndexResponse> response;
        try {
            response = restTemplate.exchange(
                    indexApiBaseUrl + "/picture/index",
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    PictureIndexResponse.class
            );
        } catch (ResourceAccessException e) {
            // 连不上或超时：绝大多数情况就是 indexApi.py 没启动。
            // 必须在这里给出明确原因，否则异常会一路冒泡到 GlobalExceptionHandler，
            // 被统一转成「系统错误」，排查时完全看不出问题出在哪个服务
            log.error("图片索引服务不可用，baseUrl = {}", indexApiBaseUrl, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "图片索引服务不可用（" + indexApiBaseUrl + "），请先启动 langgraph/indexApi.py");
        } catch (Exception e) {
            log.error("调用图片索引服务失败，pictureId = {}", picture.getId(), e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "图片索引服务调用失败：" + e.getMessage());
        }
        PictureIndexResponse body = response.getBody();
        ThrowUtils.throwIf(body == null, ErrorCode.SYSTEM_ERROR, "索引服务返回内容为空");
        return body;
    }

    /**
     * 图片标签在数据库里存的是 JSON 数组字符串，这里解析成 List
     */
    private List<String> parseTags(String tagsJson) {
        if (StrUtil.isBlank(tagsJson)) {
            return Collections.emptyList();
        }
        try {
            return JSONUtil.toList(tagsJson, String.class);
        } catch (Exception e) {
            log.warn("解析图片标签失败，tags = {}", tagsJson);
            return Collections.emptyList();
        }
    }

    /**
     * 回写索引状态；fillBack 不为空时一并补齐缺失的图片信息
     */
    private void updateIndexStatus(Long pictureId, PictureIndexStatusEnum statusEnum, Picture fillBack) {
        try {
            Picture update = fillBack == null ? new Picture() : fillBack;
            update.setId(pictureId);
            update.setIndexStatus(statusEnum.getValue());
            pictureMapper.updateById(update);
            if (fillBack != null) {
                log.info("已为图片补齐缺失信息，id = {}", pictureId);
            }
        } catch (Exception e) {
            log.error("更新图片索引状态失败，id = {}", pictureId, e);
        }
    }
}
