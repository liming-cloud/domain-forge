<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="@@PACKAGE@@.infrastructure.persistence.note.NoteFileMapper">
    <select id="count" resultType="int">
        SELECT count(*) FROM note_file WHERE note_id = #{noteId}
    </select>
    <insert id="insert">
        INSERT INTO note_file (note_id, file_id) VALUES (#{noteId}, #{fileId})
    </insert>
    <delete id="delete">
        DELETE FROM note_file WHERE note_id = #{noteId} AND file_id = #{fileId}
    </delete>
    <select id="exists" resultType="int">
        SELECT count(*) FROM note_file WHERE note_id = #{noteId} AND file_id = #{fileId}
    </select>
    <select id="list" resultType="string">
        SELECT file_id FROM note_file WHERE note_id = #{noteId} ORDER BY added_at, file_id
    </select>
</mapper>
