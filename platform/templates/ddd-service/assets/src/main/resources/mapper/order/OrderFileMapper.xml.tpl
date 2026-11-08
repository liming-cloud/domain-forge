<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="@@PACKAGE@@.infrastructure.persistence.order.OrderFileMapper">
    <select id="count" resultType="int">
        SELECT count(*) FROM order_file WHERE order_id = #{orderId}
    </select>
    <insert id="insert">
        INSERT INTO order_file (order_id, file_id) VALUES (#{orderId}, #{fileId})
    </insert>
    <delete id="delete">
        DELETE FROM order_file WHERE order_id = #{orderId} AND file_id = #{fileId}
    </delete>
    <select id="exists" resultType="int">
        SELECT count(*) FROM order_file WHERE order_id = #{orderId} AND file_id = #{fileId}
    </select>
    <select id="list" resultType="string">
        SELECT file_id FROM order_file WHERE order_id = #{orderId} ORDER BY added_at, file_id
    </select>
</mapper>
