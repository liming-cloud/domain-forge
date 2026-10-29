<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="@@PACKAGE@@.infrastructure.persistence.note.NoteMapper">
    <select id="lock" resultType="@@PACKAGE@@.infrastructure.persistence.note.NoteRow">
        SELECT id, owner_id, unit_id, title, content, version FROM note WHERE id = #{id} FOR UPDATE
    </select>
    <select id="find" resultType="@@PACKAGE@@.infrastructure.persistence.note.NoteRow">
        SELECT id, owner_id, unit_id, title, content, version FROM note WHERE id = #{id}
    </select>
    <insert id="insert">
        INSERT INTO note (id, owner_id, unit_id, title, content, version)
        VALUES (#{id}, #{ownerId}, #{unitId}, #{title}, #{content}, #{version})
    </insert>
    <update id="update">
        UPDATE note
        SET title = #{row.title}, content = #{row.content}, version = #{row.version}
        WHERE id = #{row.id} AND version = #{expected}
    </update>
</mapper>
