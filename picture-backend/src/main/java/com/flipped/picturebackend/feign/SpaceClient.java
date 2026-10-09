package com.flipped.picturebackend.feign;

import com.flipped.picturebackend.common.BaseResponse;
import com.flipped.picturebackend.model.dto.space.SpaceQueryRequest;
import com.flipped.picturebackend.model.entity.Space;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * space-service 的内部接口客户端（阶段 4b）
 * <p>
 * 单体不再读写 space / space_user 两张表，空间与成员的所有读写都走这里。
 * <p>
 * 地址策略与 {@link UserClient} 完全一致：配置了 {@code space.api.base-url} 就直接连它
 * —— **不依赖 Nacos**，Nacos 挂了也能开发；置空则退化为「按服务名 + 负载均衡」走服务发现。
 * <p>
 * 关于「登录用户是谁」：单体自己解析 JWT 拿到 userId 再传进来，而不是把原始 token 转发过去
 * —— 单体本来就要认登录态，没必要让 space-service 重复解析一遍。
 */
@FeignClient(name = "picture-space-service",
        url = "${space.api.base-url:http://127.0.0.1:8130}",
        configuration = SpaceClient.InternalTokenConfig.class)
public interface SpaceClient {

    // ==================== 读空间 ====================

    /**
     * 按 id 查空间；不存在时 data 为 null（不抛异常，方便渲染路径降级）
     */
    @GetMapping("/api/internal/space/{id}")
    BaseResponse<Space> getById(@PathVariable("id") Long id);

    /**
     * 批量查空间
     */
    @PostMapping("/api/internal/space/listByIds")
    BaseResponse<List<Space>> listByIds(@RequestBody List<Long> ids);

    /**
     * 分页查空间（管理端）
     */
    @PostMapping("/api/internal/space/list/page")
    BaseResponse<PageResult<Space>> listByPage(@RequestBody SpaceQueryRequest queryRequest);

    /**
     * 用户私人空间 id（相册模块与智能助手都依赖它）
     */
    @GetMapping("/api/internal/space/getSpaceIdByUserId")
    BaseResponse<Long> getSpaceIdByUserId(@RequestParam("userId") Long userId);

    /**
     * 「与我有关」的空间分页（我创建的 + 我加入的）
     */
    @GetMapping("/api/internal/space/listMySpaceByPage")
    BaseResponse<PageResult<Space>> listMySpaceByPage(@RequestParam("userId") Long userId,
                                                      @RequestParam("current") long current,
                                                      @RequestParam("size") long size);

    // ==================== 写空间 ====================

    /**
     * 创建空间（单体在自己的本地事务里调它）
     */
    @PostMapping("/api/internal/space/add")
    BaseResponse<Long> addSpace(@RequestBody SpaceAddPayload request, @RequestParam("userId") Long userId);

    /**
     * 删除空间（连带清理成员记录）
     */
    @PostMapping("/api/internal/space/delete")
    BaseResponse<Boolean> deleteSpace(@RequestBody DeletePayload request, @RequestParam("userId") Long userId);

    // ==================== 配额（阶段 4 唯一的跨服务写）====================

    /**
     * 读取空间配额快照
     */
    @GetMapping("/api/internal/space/quota/{id}")
    BaseResponse<Space> getQuota(@PathVariable("id") Long id);

    /**
     * 按增量调整配额；返回调整后的最新空间
     */
    @PostMapping("/api/internal/space/quota/change")
    BaseResponse<Space> changeQuota(@RequestBody QuotaChangePayload request);

    /**
     * 原子「校验 + 预占」额度（阶段 5d-b）：上传前占额度用。
     * <p>
     * 额度不足时**不抛异常**，而是返回 {@code data.limited} 非空 —— 那是业务判定，
     * 与「space-service 连不上」必须区分开（阶段 4 的教训）。
     */
    @PostMapping("/api/internal/space/quota/reserve")
    BaseResponse<QuotaReserveResult> reserveQuota(@RequestBody QuotaChangePayload request);

    /**
     * 配额预占结果。{@code limited} 非空表示额度不足（业务判定，不是故障）。
     */
    @lombok.Data
    class QuotaReserveResult {
        private String limited;
        private Space space;
    }

    // ==================== 空间成员角色 ====================

    /**
     * 解析用户在某空间中的角色（0-3）；不是成员时 data 为 null
     */
    @GetMapping("/api/internal/space-user/role")
    BaseResponse<Integer> getRole(@RequestParam("spaceId") Long spaceId,
                                  @RequestParam("userId") Long userId);

    /**
     * 批量解析角色：spaceId -> 角色值
     */
    @PostMapping("/api/internal/space-user/roleMap")
    BaseResponse<Map<Long, Integer>> getRoleMap(@RequestBody RoleMapPayload request);

    // ==================== 请求体（必须与 space-service 侧字段名一致）====================

    /**
     * 创建空间请求。刻意不直接复用 {@code SpaceAddRequest}：
     * 跨服务契约要独立于单体内部 DTO 的演进而存在（字段名一致即可）。
     */
    @lombok.Data
    class SpaceAddPayload {
        private String spaceName;
        private Integer spaceLevel;
        private Integer spaceType;
    }

    @lombok.Data
    class DeletePayload {
        private Long id;
    }

    /**
     * 配额增量请求。字段全部允许缺省（阶段 3 的教训）：缺省按 0 处理。
     */
    @lombok.Data
    class QuotaChangePayload {
        private Long spaceId;
        private Long sizeDelta;
        private Long countDelta;
        private String reason;
    }

    @lombok.Data
    class RoleMapPayload {
        private Long userId;
        private List<Long> spaceIds;
    }

    /**
     * 分页结果。
     * <p>
     * 刻意不复用 MyBatis-Plus 的 {@code Page}：它是持久层类型，跨服务契约里出现它
     * 等于把「对方也用 MyBatis-Plus」变成隐含约定。字段名按 Jackson 的 Bean 约定与
     * space-service 返回的 Page 对齐即可（records/total/current/size/pages）。
     */
    @lombok.Data
    class PageResult<T> {
        private List<T> records;
        private long total;
        private long size;
        private long current;
        private long pages;
    }

    /**
     * 内部调用统一带上约定的 token（与 user-service 的内部接口同一套机制）。
     * <p>
     * 刻意写成**不带 @Configuration 的静态内部类**：Feign 的 configuration 若被 Spring
     * 当普通配置类扫描到，会变成全局生效，污染其它 Feign 客户端。
     */
    class InternalTokenConfig {

        @Value("${internal.api.token:}")
        private String internalApiToken;

        @Bean
        public feign.RequestInterceptor internalTokenInterceptor() {
            return template -> {
                if (internalApiToken != null && !internalApiToken.isEmpty()) {
                    template.header("X-Internal-Token", internalApiToken);
                }
            };
        }
    }
}
