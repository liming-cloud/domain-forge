-- 邀请改为一次性凭据：账号记录所用邀请的摘要，唯一约束保证一份邀请最多建一个账号；“已使用”由此推导。
ALTER TABLE iam_account
    ADD COLUMN invitation_digest char(64) UNIQUE CHECK (invitation_digest ~ '^[a-f0-9]{64}$');

UPDATE iam_account a
SET invitation_digest = i.secret_digest
FROM iam_invitation i
WHERE i.consumed_by = a.id;

ALTER TABLE iam_invitation
    DROP COLUMN consumed_by;
