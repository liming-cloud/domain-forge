<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="@@PACKAGE@@.infrastructure.persistence.order.OrderMapper">
    <select id="lock" resultType="@@PACKAGE@@.infrastructure.persistence.order.OrderRow">
        SELECT id, owner_id, unit_id, status, version FROM orders WHERE id = #{id} FOR UPDATE
    </select>
    <select id="find" resultType="@@PACKAGE@@.infrastructure.persistence.order.OrderRow">
        SELECT id, owner_id, unit_id, status, version FROM orders WHERE id = #{id}
    </select>
    <select id="lines" resultType="@@PACKAGE@@.infrastructure.persistence.order.OrderLineRow">
        SELECT id, sku, quantity, unit_price_cents FROM order_line WHERE order_id = #{orderId} ORDER BY id
    </select>
    <insert id="insert">
        INSERT INTO orders (id, owner_id, unit_id, status, version)
        VALUES (#{id}, #{ownerId}, #{unitId}, #{status}, #{version})
    </insert>
    <insert id="insertLine">
        INSERT INTO order_line (order_id, id, sku, quantity, unit_price_cents)
        VALUES (#{orderId}, #{line.id}, #{line.sku}, #{line.quantity}, #{line.unitPriceCents})
    </insert>
    <update id="update">
        UPDATE orders
        SET status = #{row.status}, version = #{row.version}
        WHERE id = #{row.id} AND version = #{expected}
    </update>
    <delete id="deleteLines">
        DELETE FROM order_line WHERE order_id = #{orderId}
    </delete>
</mapper>
