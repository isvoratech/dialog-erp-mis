package lk.hayleys.dialogerp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecoveryService {
  private final JdbcTemplate db;
  private final RecoveryCalculatorClient calculator;
  private final ObjectMapper json;

  public RecoveryService(JdbcTemplate db, RecoveryCalculatorClient calculator, ObjectMapper json) {
    this.db = db;
    this.calculator = calculator;
    this.json = json;
  }

  @Transactional
  public Map<String,Object> generate(long billingBatchId, String actor) {
    Integer locked = db.queryForObject(
        "select count(*) from recovery_row where billing_batch_id=? and approval_status in ('APPROVED','REJECTED')",
        Integer.class,
        billingBatchId);
    if (locked != null && locked > 0) {
      throw new IllegalStateException("Recovery cannot be regenerated after approval/rejection has started");
    }

    List<Map<String,Object>> lines = db.queryForList(
        "select * from billing_line where billing_batch_id=? order by id",
        billingBatchId);
    if (lines.isEmpty()) throw new IllegalArgumentException("Billing batch has no billing lines: " + billingBatchId);

    int created = 0;
    int review = 0;
    BigDecimal bill = BigDecimal.ZERO;
    BigDecimal recovery = BigDecimal.ZERO;
    String formulaVersion = null;

    for (Map<String,Object> line : lines) {
      BigDecimal source = decimal(line.get("total_amount_payable"));
      BigDecimal company = decimal(line.get("company_pay"));
      if (company == null) company = BigDecimal.ZERO;
      BigDecimal other = BigDecimal.ZERO;
      String matchStatus = Objects.toString(line.get("match_status"), "unmatched");
      boolean ambiguous = "ambiguous".equalsIgnoreCase(matchStatus);

      RecoveryCalculatorClient.Result result = calculator.calculate(
          new RecoveryCalculatorClient.Input(source, company, other, matchStatus, ambiguous));
      if (result == null) throw new IllegalStateException("Recovery calculator returned no result");

      formulaVersion = result.formulaVersion();
      String approvalStatus = result.requiresReview() ? "NEEDS_REVIEW" : "DRAFT";
      String reviewNote = String.join("; ", result.reviewReasons());

      db.update(
          "insert into recovery_row(billing_line_id,billing_batch_id,mobile_norm,source_bill,company_pay,other_charges,recovery_amount,company_payable,match_status,approval_status,formula_version,review_note) values(?,?,?,?,?,?,?,?,?,?,?,?) " +
          "on conflict(billing_line_id) do update set source_bill=excluded.source_bill,company_pay=excluded.company_pay,other_charges=excluded.other_charges,recovery_amount=excluded.recovery_amount,company_payable=excluded.company_payable,match_status=excluded.match_status,approval_status=excluded.approval_status,formula_version=excluded.formula_version,review_note=excluded.review_note,recovery_override=null,company_payable_override=null,updated_at=now()",
          line.get("id"),
          billingBatchId,
          line.get("mobile_norm"),
          source,
          company,
          other,
          result.recoveryAmount(),
          result.companyPayable(),
          matchStatus,
          approvalStatus,
          result.formulaVersion(),
          reviewNote);

      created++;
      if (result.requiresReview()) review++;
      bill = bill.add(source);
      recovery = recovery.add(result.recoveryAmount());
    }

    audit(actor, "RECOVERY_GENERATED", "billing_batch", billingBatchId,
        Map.of("rows", created, "needsReview", review, "grossBill", bill, "grossRecovery", recovery,
            "formulaVersion", formulaVersion == null ? "unknown" : formulaVersion));

    return Map.of(
        "billingBatchId", billingBatchId,
        "rows", created,
        "needsReview", review,
        "grossBill", bill,
        "grossRecovery", recovery,
        "formulaVersion", formulaVersion == null ? "unknown" : formulaVersion);
  }

  public List<Map<String,Object>> reviewQueue() {
    return db.queryForList(
        "select r.*, b.mobile_display, b.connection_id, " +
        "coalesce(r.recovery_override,r.recovery_amount) as effective_recovery, " +
        "coalesce(r.company_payable_override,r.company_payable) as effective_company_payable " +
        "from recovery_row r join billing_line b on b.id=r.billing_line_id " +
        "where r.approval_status='NEEDS_REVIEW' order by r.id");
  }

  @Transactional
  public void decide(
      long id,
      String decision,
      String note,
      String recoveryOverride,
      String companyPayableOverride,
      String actor) {

    String normalized = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("APPROVED", "REJECTED", "DRAFT").contains(normalized)) {
      throw new IllegalArgumentException("Invalid decision");
    }
    if (("APPROVED".equals(normalized) || "REJECTED".equals(normalized)) && (note == null || note.isBlank())) {
      throw new IllegalArgumentException("A decision note is required");
    }

    Map<String,Object> before = db.queryForMap("select * from recovery_row where id=? for update", id);
    BigDecimal ro = parseNonNegative(recoveryOverride);
    BigDecimal co = parseNonNegative(companyPayableOverride);

    db.update(
        "update recovery_row set approval_status=?,review_note=?,recovery_override=?,company_payable_override=?,updated_at=now() where id=?",
        normalized,
        blankToNull(note),
        ro,
        co,
        id);

    Map<String,Object> details = new LinkedHashMap<>();
    details.put("fromStatus", before.get("approval_status"));
    details.put("toStatus", normalized);
    details.put("reason", note == null ? "" : note);
    details.put("recoveryOverride", ro);
    details.put("companyPayableOverride", co);
    details.put("calculatedRecovery", before.get("recovery_amount"));
    details.put("calculatedCompanyPayable", before.get("company_payable"));
    audit(actor, "RECOVERY_DECISION", "recovery_row", id, details);
  }

  private void audit(String actor, String action, String entityType, long entityId, Map<String,Object> details) {
    try {
      db.update(
          "insert into audit_log(actor,action,entity_type,entity_id,details) values(?,?,?,?,?::jsonb)",
          actor == null || actor.isBlank() ? "unknown" : actor,
          action,
          entityType,
          entityId,
          json.writeValueAsString(details));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialise audit details", e);
    }
  }

  private static BigDecimal decimal(Object value) {
    if (value == null) return null;
    try {
      return new BigDecimal(String.valueOf(value));
    } catch (Exception e) {
      return BigDecimal.ZERO;
    }
  }

  private static BigDecimal parseNonNegative(String value) {
    if (value == null || value.isBlank()) return null;
    BigDecimal n = new BigDecimal(value);
    if (n.signum() < 0) throw new IllegalArgumentException("Overrides cannot be negative");
    return n;
  }

  private static String blankToNull(String value) {
    if (value == null) return null;
    String v = value.trim();
    return v.isEmpty() ? null : v;
  }
}
