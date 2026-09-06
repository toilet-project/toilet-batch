package com.geupddong.account;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Erasure contract v1. Keep this source identical in toilet-api and toilet-batch.
 * Caller must lock app_user, verify withdrawal deadline, clear Redis and open a transaction.
 * No SQL exception may be swallowed: an unknown reference must roll back the whole account.
 */
public final class AccountErasureSql {
    private AccountErasureSql() { }

    public static void erase(JdbcTemplate jdbc, long id) {
        jdbc.update("UPDATE audit_log SET detail_json=NULL WHERE target_type='TOILET_REPORT' AND target_id IN "
                + "(SELECT report_id FROM toilet_report WHERE reporter_user_id=?)", id);
        jdbc.update("UPDATE toilet_report SET reporter_user_id=NULL, reason='탈퇴한 사용자 — 사유 파기', "
                + "review_note=NULL, active_request_key=NULL WHERE reporter_user_id=?", id);
        jdbc.update("UPDATE toilet_report SET reviewed_by_user_id=NULL, review_note=NULL WHERE reviewed_by_user_id=?", id);
        jdbc.update("UPDATE coordinate_revision SET applied_by_user_id=NULL WHERE applied_by_user_id=?", id);
        jdbc.update("UPDATE coordinate_quality_review SET reviewed_by_user_id=NULL, review_note=NULL WHERE reviewed_by_user_id=?", id);
        jdbc.update("UPDATE user_role SET granted_by_user_id=NULL WHERE granted_by_user_id=?", id);
        jdbc.update("UPDATE audit_log SET actor_user_id=NULL, actor_erased=TRUE, detail_json=NULL WHERE actor_user_id=?", id);
        jdbc.update("UPDATE audit_log SET target_id=NULL, detail_json=NULL WHERE target_type='USER' AND target_id=?", id);
        jdbc.update("DELETE FROM user_notification WHERE user_id=?", id);
        jdbc.update("DELETE FROM user_policy_consent WHERE user_id=?", id);
        jdbc.update("DELETE FROM user_role WHERE user_id=?", id);
        jdbc.update("DELETE FROM user_social_account WHERE user_id=?", id);
        jdbc.update("DELETE FROM account_withdrawal WHERE user_id=?", id);
        if (jdbc.update("DELETE FROM app_user WHERE user_id=? AND status='WITHDRAWN'", id) != 1)
            throw new IllegalStateException("ERASURE_STATE_CHANGED");
    }
}
