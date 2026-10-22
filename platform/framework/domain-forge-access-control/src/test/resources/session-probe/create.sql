CREATE SCHEMA ${schema};
CREATE TABLE ${schema}.probe_account(id BIGINT PRIMARY KEY, enabled BOOLEAN NOT NULL, version INT NOT NULL);
INSERT INTO ${schema}.probe_account VALUES(1,true,1);
CREATE TABLE ${schema}.probe_commit(token_digest VARCHAR(64) PRIMARY KEY);
CREATE TABLE ${schema}.probe_deferred(value INT UNIQUE DEFERRABLE INITIALLY DEFERRED);
