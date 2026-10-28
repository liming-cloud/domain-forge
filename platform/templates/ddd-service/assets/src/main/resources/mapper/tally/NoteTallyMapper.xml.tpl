<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="@@PACKAGE@@.infrastructure.persistence.tally.NoteTallyMapper">
    <insert id="increment">
        INSERT INTO note_tally (owner_id, notes) VALUES (#{ownerId}, 1)
        ON CONFLICT (owner_id) DO UPDATE SET notes = note_tally.notes + 1
    </insert>

    <select id="count" resultType="java.lang.Long">
        SELECT notes FROM note_tally WHERE owner_id = #{ownerId}
    </select>
</mapper>
