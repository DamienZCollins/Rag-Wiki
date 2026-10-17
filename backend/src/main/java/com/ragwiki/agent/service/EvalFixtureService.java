package com.ragwiki.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ragwiki.agent.api.EvalFixtureApi;
import com.ragwiki.agent.entity.OrderInfo;
import com.ragwiki.agent.mapper.OrderInfoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@link EvalFixtureApi} 的实现：把订单夹具恢复成 sql/seed.sql 里的基线状态。
 *
 * <p>状态取值与 sql/seed.sql 保持一致，改种子数据时这里要跟着改。
 * 只列会被工具改写的订单——查询类订单没有可漂移的状态，不需要复位。
 */
@Service
public class EvalFixtureService implements EvalFixtureApi {

    private static final Logger log = LoggerFactory.getLogger(EvalFixtureService.class);

    /** 订单号 -> 基线状态。与 sql/seed.sql 的 order_info 保持一致 */
    private static final Map<String, String> BASELINE_STATUS = Map.of(
            "SO20250901001", "DELIVERED",
            "SO20250903002", "REFUNDING",
            "SO20250912003", "SHIPPED",
            "SO20250918004", "DELIVERED",
            "SO20250925005", "PAID",
            "SO20250928006", "CLOSED",
            "SO20251001007", "SHIPPED",
            "SO20251003008", "DELIVERED");

    private final OrderInfoMapper orderInfoMapper;

    public EvalFixtureService(OrderInfoMapper orderInfoMapper) {
        this.orderInfoMapper = orderInfoMapper;
    }

    @Override
    public int restoreOrderFixtures() {
        List<OrderInfo> orders = orderInfoMapper.selectList(
                Wrappers.<OrderInfo>lambdaQuery().in(OrderInfo::getOrderNo, BASELINE_STATUS.keySet()));
        int restored = 0;
        for (OrderInfo order : orders) {
            String baseline = BASELINE_STATUS.get(order.getOrderNo());
            if (baseline != null && !baseline.equals(order.getStatus())) {
                OrderInfo update = new OrderInfo();
                update.setId(order.getId());
                update.setStatus(baseline);
                update.setUpdatedAt(Instant.now());
                orderInfoMapper.updateById(update);
                log.info("订单夹具复位：{} {} -> {}", order.getOrderNo(), order.getStatus(), baseline);
                restored++;
            }
        }
        return restored;
    }
}
