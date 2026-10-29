<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="@@PACKAGE@@.infrastructure.archive.NoteArchiveMapper">
    <resultMap id="archived" type="@@PACKAGE@@.application.port.archive.ArchivedNote">
        <constructor>
            <arg column="note_id" javaType="_long"/>
            <arg column="owner_id" javaType="_long"/>
            <arg column="unit_id" javaType="java.lang.Long"/>
            <arg column="title" javaType="java.lang.String"/>
            <arg column="content" javaType="java.lang.String"/>
            <arg column="version" javaType="_long"/>
            <arg column="archived_at" javaType="java.time.Instant"/>
        </constructor>
    </resultMap>
    <insert id="upsert">
        INSERT INTO note_archive (note_id, owner_id, unit_id, title, content, version, archived_at)
        VALUES (#{note.noteId}, #{note.ownerId}, #{note.unitId}, #{note.title}, #{note.content}, #{note.version},
                #{note.archivedAt})
        ON CONFLICT (note_id) DO UPDATE
        SET title = EXCLUDED.title, content = EXCLUDED.content, unit_id = EXCLUDED.unit_id,
            version = EXCLUDED.version, archived_at = EXCLUDED.archived_at
    </insert>
    <select id="find" resultMap="archived">
        SELECT note_id, owner_id, unit_id, title, content, version, archived_at FROM note_archive
        WHERE note_id = #{noteId}
    </select>
</mapper>
