package lk.hayleys.dialogerp.service;

import java.util.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BillingReportService {
  private final JdbcTemplate db;

  public BillingReportService(JdbcTemplate db) {
    this.db = db;
  }

  public Map<String,Object> monthlyBilling(long billingBatchId) {
    Map<String,Object> batch;
    try {
      batch = db.queryForMap(
          "select bb.id as billing_batch_id, i.id as batch_id, i.filename, i.status, " +
          "i.source_rows, i.bill_total, bb.period_end, bb.contract_no, i.created_at " +
          "from billing_batch bb join import_batch i on i.id=bb.import_batch_id where bb.id=?",
          billingBatchId);
    } catch (EmptyResultDataAccessException e) {
      throw new IllegalArgumentException("Billing batch not found: " + billingBatchId);
    }

    List<Map<String,Object>> lines = db.queryForList(
        "select bl.id, bl.mobile_display, bl.mobile_norm, bl.match_status, bl.connection_id, " +
        "bl.charges_for_bill_period, bl.total_amount_payable, bl.company_pay, " +
        "bl.raw_json->>'previous due amount' as previous_due_amount, " +
        "bl.raw_json->>'payment' as payment, " +
        "bl.raw_json->>'total usage charges' as total_usage_charges, " +
        "bl.raw_json->>'idd' as idd, " +
        "bl.raw_json->>'roaming' as roaming, " +
        "bl.raw_json->>'vas' as vas, " +
        "bl.raw_json->>'discounts/bill adjustments' as discounts_bill_adjustments, " +
        "bl.raw_json->>'balance adjustments' as balance_adjustments, " +
        "bl.raw_json->>'commitment charges' as commitment_charges, " +
        "bl.raw_json->>'late payment charges' as late_payment_charges, " +
        "bl.raw_json->>'government taxes' as government_taxes, " +
        "bl.raw_json->>'vat' as vat, " +
        "bl.raw_json->>'add to bill charges' as add_to_bill_charges " +
        "from billing_line bl where bl.billing_batch_id=? order by bl.id",
        billingBatchId);

    List<Map<String,Object>> issueSummary = db.queryForList(
        "select qi.issue_type, qi.severity, count(*) as count " +
        "from quality_issue qi join billing_batch bb on bb.import_batch_id=qi.import_batch_id " +
        "where bb.id=? and qi.resolved=false " +
        "group by qi.issue_type, qi.severity order by qi.severity desc, qi.issue_type",
        billingBatchId);

    Map<String,Object> totals = db.queryForMap(
        "select count(*) as lines, " +
        "coalesce(sum(charges_for_bill_period),0) as charges_for_bill_period, " +
        "coalesce(sum(total_amount_payable),0) as total_amount_payable, " +
        "count(*) filter (where match_status='unmatched') as unmatched, " +
        "count(*) filter (where match_status<>'unmatched') as matched " +
        "from billing_line where billing_batch_id=?",
        billingBatchId);

    Map<String,Object> invoiceMeta = Collections.emptyMap();
    if (!lines.isEmpty()) {
      invoiceMeta = db.queryForMap(
          "select " +
          "raw_json->>'_invoiceNumber' as invoice_number, " +
          "raw_json->>'_invoiceDate' as invoice_date, " +
          "raw_json->>'_corporateCode' as corporate_code, " +
          "raw_json->>'_billPeriod' as bill_period, " +
          "raw_json->>'_format' as source_format " +
          "from billing_line where billing_batch_id=? order by id limit 1",
          billingBatchId);
    }

    Map<String,Object> result = new LinkedHashMap<>();
    result.put("batch", batch);
    result.put("invoice", invoiceMeta);
    result.put("totals", totals);
    result.put("issues", issueSummary);
    result.put("lines", lines);
    return result;
  }
}
