<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="@@PACKAGE@@.infrastructure.fixture.OrderFixtureMapper">
    <update id="reset">TRUNCATE orders, order_line, order_file, domainforge_file, domainforge_outbox</update>
    <select id="outboxCount" resultType="int">SELECT count(*) FROM domainforge_outbox</select>
    <select id="onlyOutboxEvent" resultType="map">SELECT topic, event_type, event_key, payload FROM domainforge_outbox</select>
    <select id="lines" resultType="int">SELECT count(*) FROM order_line WHERE order_id = #{orderId}</select>
    <select id="deletedFiles" resultType="int">
        SELECT count(*) FROM domainforge_file WHERE id = #{id} AND deleted_at IS NOT NULL
    </select>
</mapper>
