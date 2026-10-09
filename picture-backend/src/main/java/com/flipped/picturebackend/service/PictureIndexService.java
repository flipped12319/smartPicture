package com.flipped.picturebackend.service;

import java.util.List;

/**
 * 图片向量索引服务
 * <p>
 * 负责在图片发生变更时，异步通知 Python 索引服务生成标签并写入向量库。
 * 所有方法都是「尽力而为」：失败只记录状态与日志，绝不阻塞主业务流程。
 */
public interface PictureIndexService {

    /**
     * 异步为图片建立向量索引（不会写回图片表）
     * <p>
     * 触发时机：图片信息被编辑 —— 编辑不是审核动作，只重建索引、不做补充
     *
     * @param pictureId 图片 id
     */
    void indexPictureAsync(Long pictureId);

    /**
     * 异步建立向量索引，并把模型生成的名称/简介/分类/标签补齐到图片表
     * <p>
     * 只会补「原本为空」的字段，不会覆盖用户填写的内容。
     * <p>
     * 触发时机：图片进入「已过审」状态的时刻，具体有三条路径：
     * <ol>
     *     <li>私人空间上传 —— 空间创建者自动过审</li>
     *     <li>管理员上传公共图库 —— 管理员自动过审</li>
     *     <li>普通用户上传公共图库 —— 管理员点击通过</li>
     * </ol>
     *
     * @param pictureId 图片 id
     */
    void indexPictureAndFillAsync(Long pictureId);

    /**
     * **同步**为图片建索引，失败时**抛异常**（阶段 5b 新增）
     * <p>
     * 与上面两个 {@code ...Async} 的区别只有两点，但都很关键：
     * <ol>
     *     <li>同步执行 —— 调用方自己决定在哪个线程上跑（现在是 MQ 消费者线程）；</li>
     *     <li>失败抛异常 —— 让调用方（MQ 消费者）能感知失败并 reject 消息，
     *         从而走「延迟重试 → 死信队列 → 人工重放」这条路。
     *         老的 {@code doIndex} 把失败吞成 {@code indexStatus=2}，
     *         消息会被正常 ack，于是**这条索引就永远丢了**（阶段 5 要修的就是这个）。</li>
     * </ol>
     * 直接调用方要自己接住异常；MQ 那条路会由 {@code MqConsumerSupport} 处理。
     *
     * @param pictureId       图片 id
     * @param fillBlankFields 是否把模型生成的内容回填到图片表
     */
    void indexPictureNow(Long pictureId, boolean fillBlankFields);

    /**
     * 语义检索图片
     * <p>
     * 只返回召回的图片 id（按相似度从高到低），图片正文与权限由调用方回表 MySQL 处理。
     * 检索服务不可用时返回空集合而不抛异常，调用方据此降级为关键词搜索。
     *
     * @param query   查询文本
     * @param spaceId 空间 id；null 表示公共图库
     */
    List<Long> searchPictureIds(String query, Long spaceId);

    /**
     * 智能补充图片信息：补齐为空的名称、分类、简介、标签
     * <p>
     * 与「审核通过时」的自动补充共用同一套逻辑，都只补空字段、不覆盖已有内容。
     * 这里刻意做成同步：详情页手动点击后需要立即拿到结果，
     * 而且本次调用本身就会顺带把向量索引重建好。
     *
     * @param pictureId 图片 id
     * @return 本次被补充的字段名（中文，便于直接提示用户）；空列表表示没有可补充的内容
     */
    List<String> autoFillPictureInfo(Long pictureId);

    /**
     * 重建向量索引（用于补齐存量图片）
     * <p>
     * 只挑选「已过审」的图片；任务提交到索引线程池后立即返回，不阻塞调用方。
     *
     * @param onlyMissing 是否只处理未索引或索引失败的图片
     * @return 本次提交处理的图片数量
     */
    int rebuildIndex(boolean onlyMissing);

    /**
     * 异步删除图片的向量索引
     * <p>
     * 触发时机：图片被删除（单张或批量）
     *
     * @param pictureIds 图片 id 列表
     */
    void removePictureIndexAsync(List<Long> pictureIds);
}
