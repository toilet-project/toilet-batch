-- Synthetic V11-shaped fixture; no production data. Includes an ACTIVE pre-withdrawal backup account.
CREATE DATABASE toilet_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE toilet_db;
CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY, name VARCHAR(100));
INSERT INTO toilet VALUES(1,'synthetic facility');
CREATE TABLE app_user(user_id BIGINT PRIMARY KEY, status VARCHAR(30), created_at DATETIME NOT NULL);
INSERT INTO app_user VALUES(1,'ACTIVE','2000-01-01 00:00:00'),(2,'ACTIVE','2000-01-02 00:00:00');
CREATE TABLE account_withdrawal(user_id BIGINT PRIMARY KEY, FOREIGN KEY(user_id) REFERENCES app_user(user_id));
CREATE TABLE toilet_report(report_id BIGINT PRIMARY KEY, reporter_user_id BIGINT, reviewed_by_user_id BIGINT,
 reason VARCHAR(100), review_note VARCHAR(100), active_request_key VARCHAR(100), proposed_latitude DECIMAL(10,7),
 FOREIGN KEY(reporter_user_id) REFERENCES app_user(user_id), FOREIGN KEY(reviewed_by_user_id) REFERENCES app_user(user_id));
INSERT INTO toilet_report VALUES(1,1,1,'synthetic reason','synthetic review','synthetic key',37.5);
CREATE TABLE audit_log(actor_user_id BIGINT, actor_erased BOOLEAN DEFAULT FALSE, target_type VARCHAR(50), target_id BIGINT, detail_json VARCHAR(100), FOREIGN KEY(actor_user_id) REFERENCES app_user(user_id));
INSERT INTO audit_log VALUES(1,FALSE,'USER',1,'synthetic');
CREATE TABLE coordinate_revision(applied_by_user_id BIGINT, FOREIGN KEY(applied_by_user_id) REFERENCES app_user(user_id));
INSERT INTO coordinate_revision VALUES(1);
CREATE TABLE coordinate_quality_review(reviewed_by_user_id BIGINT, review_note VARCHAR(100), FOREIGN KEY(reviewed_by_user_id) REFERENCES app_user(user_id));
INSERT INTO coordinate_quality_review VALUES(1,'synthetic');
CREATE TABLE user_role(user_id BIGINT, granted_by_user_id BIGINT, FOREIGN KEY(user_id) REFERENCES app_user(user_id), FOREIGN KEY(granted_by_user_id) REFERENCES app_user(user_id));
INSERT INTO user_role VALUES(1,1),(2,2);
CREATE TABLE user_notification(user_id BIGINT, FOREIGN KEY(user_id) REFERENCES app_user(user_id));
INSERT INTO user_notification VALUES(1),(2);
CREATE TABLE user_policy_consent(user_id BIGINT, FOREIGN KEY(user_id) REFERENCES app_user(user_id));
INSERT INTO user_policy_consent VALUES(1),(2);
CREATE TABLE user_social_account(user_id BIGINT, FOREIGN KEY(user_id) REFERENCES app_user(user_id));
INSERT INTO user_social_account VALUES(1),(2);
