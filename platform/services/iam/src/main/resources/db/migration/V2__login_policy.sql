-- 保留V1的固定策略身份；运行时初始化可登记受支持的新项目策略。
ALTER TABLE iam_login_protection
  ADD COLUMN policy_digest varchar(64) NOT NULL DEFAULT 'e8919bbcfcd5fa7292f4a171ef50b41cf94485446d3109131ce4c640ca4639a1',
  ADD COLUMN account_attempts integer NOT NULL DEFAULT 5 CHECK(account_attempts BETWEEN 1 AND 5),
  ADD COLUMN account_window_seconds integer NOT NULL DEFAULT 600 CHECK(account_window_seconds BETWEEN 600 AND 3600),
  ADD COLUMN address_attempts integer NOT NULL DEFAULT 30 CHECK(address_attempts BETWEEN 1 AND 30),
  ADD COLUMN address_window_seconds integer NOT NULL DEFAULT 60 CHECK(address_window_seconds BETWEEN 60 AND 600);
