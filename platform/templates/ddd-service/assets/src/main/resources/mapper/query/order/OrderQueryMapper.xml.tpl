<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="@@PACKAGE@@.infrastructure.query.order.OrderQueryMapper">
    <select id="owned" resultType="@@PACKAGE@@.application.query.order.OrderView">
        SELECT id, owner_id, unit_id, status,
               (SELECT COALESCE(SUM(l.quantity * l.unit_price_cents), 0) FROM order_line l WHERE l.order_id = orders.id)
                   AS total_cents,
               version
        FROM orders
        WHERE owner_id = #{owner} AND id &gt; #{after}
        ORDER BY id LIMIT #{limit}
    </select>
    <!-- 列的顺序与 OrderView 的构造参数一致（MyBatis 按类型顺序匹配记录构造器）。范围映射：ALL 不加条件；否则本人 OR 所属组织，两者都没有时调用方不会执行本查询。 -->
    <select id="visible" resultType="@@PACKAGE@@.application.query.order.OrderView">
        SELECT id, owner_id, unit_id, status,
               (SELECT COALESCE(SUM(l.quantity * l.unit_price_cents), 0) FROM order_line l WHERE l.order_id = orders.id)
                   AS total_cents,
               version
        FROM orders
        WHERE id &gt; #{filter.after}
        <if test="!filter.all()">
            AND (false
            <if test="filter.self()">OR owner_id = #{filter.account}</if>
            <if test="!filter.units().isEmpty()">
                OR unit_id IN
                <foreach collection="filter.units" item="unit" open="(" separator="," close=")">#{unit}</foreach>
            </if>
            )
        </if>
        ORDER BY id LIMIT #{filter.limit}
    </select>
</mapper>
