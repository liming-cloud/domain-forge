<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="@@PACKAGE@@.infrastructure.persistence.tally.OrderTallyMapper">
    <insert id="increment">
        INSERT INTO order_tally (owner_id, orders) VALUES (#{ownerId}, 1)
        ON CONFLICT (owner_id) DO UPDATE SET orders = order_tally.orders + 1
    </insert>

    <select id="count" resultType="java.lang.Long">
        SELECT orders FROM order_tally WHERE owner_id = #{ownerId}
    </select>
</mapper>
