package lk.hayleys.dialogerp.service;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
  private final JdbcTemplate db;

  public DashboardService(JdbcTemplate db) {
    this.db = db;
  }

  public Map<String,Object> dashboard() {
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("connections", scalar("select count(*) from connection"));
    result.put("openQualityIssues", scalar("select count(*) from quality_issue where resolved=false"));
    result.put("imports", scalar("select count(*) from import_batch where status='COMPLETED'"));

    List<Map<String,Object>> latest = db.queryForList(
        "select id,period_end,contract_no,created_at from billing_batch order by period_end desc nulls last, id desc limit 1");
    if (latest.isEmpty()) {
      result.put("latestBatch", null);
      result.put("billingLines", 0);
      result.put("grossBill", 0);
      result.put("matched", 0);
      result.put("unmatched", 0);
      result.put("needsReview", 0);
      result.put("approved", 0);
      result.put("rejected", 0);
      return result;
    }

    Map<String,Object> batch = latest.get(0);
    long batchId = ((Number) batch.get("id")).longValue();
    result.put("latestBatch", batch);
    result.put("billingLines", scalar("select count(*) from billing_line where billing_batch_id=?", batchId));
    result.put("grossBill", scalar("select coalesce(sum(total_amount_payable),0) from billing_line where billing_batch_id=?", batchId));
    result.put("matched", scalar("select count(*) from billing_line where billing_batch_id=? and match_status<>'unmatched'", batchId));
    result.put("unmatched", scalar("select count(*) from billing_line where billing_batch_id=? and match_status='unmatched'", batchId));
    result.put("needsReview", scalar("select count(*) from recovery_row where billing_batch_id=? and approval_status='NEEDS_REVIEW'", batchId));
    result.put("approved", scalar("select count(*) from recovery_row where billing_batch_id=? and approval_status='APPROVED'", batchId));
    result.put("rejected", scalar("select count(*) from recovery_row where billing_batch_id=? and approval_status='REJECTED'", batchId));
    return result;
  }

  private Object scalar(String sql, Object... args) {
    Object value = db.queryForObject(sql, Object.class, args);
    return value == null ? 0 : value;
  }
}
