-- Member growth is private account data. Every member-owned row is removed with app_user.
-- Policy targets are frozen at explicit initialization, never by a dry-run or page view.
CREATE TABLE growth_policy_snapshot (
    policy_version VARCHAR(32) NOT NULL PRIMARY KEY,
    initialized_at DATETIME(6) NOT NULL,
    target_count INT NOT NULL,
    review_xp INT NOT NULL,
    checkin_xp INT NOT NULL,
    district_xp INT NOT NULL,
    bronze_xp INT NOT NULL,
    silver_xp INT NOT NULL,
    gold_xp INT NOT NULL,
    bronze_facilities INT NOT NULL,
    silver_facilities INT NOT NULL,
    gold_facilities INT NOT NULL,
    silver_coverage_percent INT NOT NULL,
    gold_coverage_percent INT NOT NULL,
    CONSTRAINT ck_growth_target_count CHECK (target_count >= 0)
);

CREATE TABLE growth_policy_target (
    policy_version VARCHAR(32) NOT NULL,
    sigungu_code CHAR(5) NOT NULL,
    sido_code CHAR(2) NOT NULL,
    sido_name VARCHAR(50) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    PRIMARY KEY (policy_version, sigungu_code),
    KEY idx_growth_target_sido (policy_version, sido_code, sigungu_code),
    CONSTRAINT fk_growth_target_policy FOREIGN KEY (policy_version)
        REFERENCES growth_policy_snapshot(policy_version)
);

CREATE TABLE growth_account (
    user_id BIGINT NOT NULL PRIMARY KEY,
    total_xp BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_growth_account_user FOREIGN KEY (user_id)
        REFERENCES app_user(user_id) ON DELETE CASCADE,
    CONSTRAINT ck_growth_total CHECK (total_xp >= 0)
);

-- award_key is intentionally nullable: removing an author's link scrubs the facility/region key
-- while preserving a source-free accounting record for the matching negative XP event.
CREATE TABLE growth_award (
    award_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    award_kind VARCHAR(32) NOT NULL,
    award_key VARCHAR(80) NULL,
    policy_version VARCHAR(32) NOT NULL,
    xp_amount INT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    awarded_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    scrubbed_at DATETIME(6) NULL,
    UNIQUE KEY uk_growth_award_source (user_id, award_kind, award_key),
    KEY idx_growth_award_user_active (user_id, active, award_kind),
    CONSTRAINT fk_growth_award_user FOREIGN KEY (user_id)
        REFERENCES app_user(user_id) ON DELETE CASCADE,
    CONSTRAINT ck_growth_award_xp CHECK (xp_amount > 0)
);

CREATE TABLE growth_xp_event (
    event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    award_id BIGINT NULL,
    delta_xp INT NOT NULL,
    event_kind VARCHAR(16) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    happened_at DATETIME(6) NOT NULL,
    KEY idx_growth_event_user (user_id, event_id),
    CONSTRAINT fk_growth_event_user FOREIGN KEY (user_id)
        REFERENCES app_user(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_growth_event_award FOREIGN KEY (award_id)
        REFERENCES growth_award(award_id) ON DELETE SET NULL,
    CONSTRAINT ck_growth_delta CHECK (delta_xp <> 0)
);

-- Active review evidence exists only while the review remains linked to its author.
CREATE TABLE growth_review_evidence (
    review_key CHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    toilet_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    KEY idx_growth_evidence_user (user_id, toilet_id),
    CONSTRAINT fk_growth_evidence_user FOREIGN KEY (user_id)
        REFERENCES app_user(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_growth_evidence_toilet FOREIGN KEY (toilet_id)
        REFERENCES toilet(toilet_id) ON DELETE CASCADE
);

-- An administrative exclusion does not contain the author ID or review text.
CREATE TABLE growth_review_exclusion (
    review_id BIGINT NOT NULL PRIMARY KEY,
    review_key CHAR(36) NOT NULL,
    reason VARCHAR(200) NOT NULL,
    excluded_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_growth_exclusion_review FOREIGN KEY (review_id)
        REFERENCES toilet_review(review_id) ON DELETE CASCADE
);
