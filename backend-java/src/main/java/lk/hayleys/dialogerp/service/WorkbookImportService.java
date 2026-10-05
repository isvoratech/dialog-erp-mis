package lk.hayleys.dialogerp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class WorkbookImportService {
  private static final Pattern NON_ID = Pattern.compile("[\\s\\-()]+");
  private static final Set<String> REQUIRED_LEGACY_BILL_HEADERS = Set.of("mobile", "total amount payable");
  private static final Set<String> REQUIRED_INVOICE_HEADERS = Set.of(
      "mobile no", "charges for bill period", "total amount payable");
  private static final BigDecimal TOTAL_TOLERANCE = new BigDecimal("0.05");

  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final DataFormatter formatter = new DataFormatter();

  public WorkbookImportService(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  @Transactional
  public Map<String,Object> importWorkbook(MultipartFile file, LocalDate periodEnd, String contractNo) throws Exception {
    if (file == null || file.isEmpty()) throw new IllegalArgumentException("Workbook is empty");

    byte[] bytes = file.getBytes();
    String hash = hex(MessageDigest.getInstance("SHA-256").digest(bytes));

    try (InputStream in = new ByteArrayInputStream(bytes); Workbook wb = WorkbookFactory.create(in)) {
      Sheet invoice = wb.getSheet("Invoice");
      if (invoice != null && hasHeader(invoice, "Mobile No", 80)) {
        return importDialogInvoice(file, hash, invoice, periodEnd, contractNo);
      }
      return importLegacyCombined(file, hash, wb, periodEnd, contractNo);
    }
  }

  private Map<String,Object> importDialogInvoice(
      MultipartFile file,
      String hash,
      Sheet invoice,
      LocalDate requestedPeriodEnd,
      String requestedContractNo) throws Exception {

    int headerRow = findHeader(invoice, "Mobile No", 80);
    Map<String,Integer> h = headers(invoice, headerRow);
    validateHeaders(h, REQUIRED_INVOICE_HEADERS, invoice.getSheetName());

    String invoiceNumber = findLabelValue(invoice, "INVOICE NUMBER", headerRow);
    String invoiceDate = findLabelValue(invoice, "INVOICE DATE", headerRow);
    String corporateCode = findLabelValue(invoice, "CORPORATE CODE", headerRow);
    String billPeriod = findLabelValue(invoice, "Bill Period", headerRow);
    LocalDate invoicePeriodEnd = parseBillPeriodEnd(billPeriod);
    LocalDate effectivePeriodEnd = requestedPeriodEnd != null ? requestedPeriodEnd : invoicePeriodEnd;
    String effectiveContractNo = firstNonBlank(requestedContractNo, corporateCode);

    Map<String,Object> existing = existingByHash(hash);
    if (existing == null) {
      existing = existingDialogInvoice(invoiceNumber, corporateCode, billPeriod);
    }
    if (existing != null) {
      Map<String,Object> result = new LinkedHashMap<>();
      result.put("batchId", existing.get("id"));
      result.put("billingBatchId", existing.get("billing_batch_id"));
      result.put("format", "DIALOG_INVOICE");
      result.put("invoiceNumber", invoiceNumber);
      result.put("corporateCode", corporateCode);
      result.put("billPeriod", billPeriod);
      result.put("idempotent", true);
      return result;
    }

    BigDecimal summaryCharges = findLabelNumber(invoice, "Total Charges for Bill Period", headerRow);
    BigDecimal summaryPayable = findLabelNumber(invoice, "Total Amount Payable", headerRow);

    long batchId = db.queryForObject(
        "insert into import_batch(filename,source_hash,period_end,contract_no,status) values(?,?,?,?,?) returning id",
        Long.class,
        file.getOriginalFilename(),
        hash,
        effectivePeriodEnd,
        blankToNull(effectiveContractNo),
        "IMPORTING");

    long billingBatchId = db.queryForObject(
        "insert into billing_batch(import_batch_id,period_end,contract_no) values(?,?,?) returning id",
        Long.class,
        batchId,
        effectivePeriodEnd,
        blankToNull(effectiveContractNo));

    int sourceRows = 0;
    BigDecimal totalPayable = BigDecimal.ZERO;
    BigDecimal totalCharges = BigDecimal.ZERO;
    Set<String> seenMobiles = new HashSet<>();

    for (int r = headerRow + 1; r <= invoice.getLastRowNum(); r++) {
      String rawMobile = cell(invoice, r, h, "Mobile No");
      if (rawMobile == null || rawMobile.isBlank()) continue;
      if (!rawMobile.matches(".*\\d.*")) continue;

      String mobile = normalizePhone(rawMobile);
      if (mobile == null) continue;

      if (!seenMobiles.add(mobile)) {
        throw new IllegalArgumentException(
            "Duplicate Mobile No in Invoice: " + rawMobile + " at Excel row " + (r + 1));
      }

      BigDecimal charges = requiredNumber(
          cell(invoice, r, h, "Charges for Bill Period"),
          "Charges for Bill Period", rawMobile, r + 1);
      BigDecimal payable = requiredNumber(
          cell(invoice, r, h, "Total Amount Payable"),
          "Total Amount Payable", rawMobile, r + 1);

      Long connectionId = findConnection(mobile);
      String match = connectionId == null ? "unmatched" : "matched_voice";

      Map<String,Object> payload = rowByHeaders(invoice, r, h);
      payload.put("_format", "DIALOG_INVOICE");
      payload.put("_invoiceNumber", invoiceNumber);
      payload.put("_invoiceDate", invoiceDate);
      payload.put("_corporateCode", corporateCode);
      payload.put("_billPeriod", billPeriod);
      payload.put("_rawMobile", rawMobile);
      payload.put("_normalizedMobile", mobile);

      db.update(
          "insert into billing_line(billing_batch_id,mobile_norm,mobile_display,total_amount_payable,charges_for_bill_period,company_pay,match_status,connection_id,raw_json) values(?,?,?,?,?,?,?,?,?::jsonb)",
          billingBatchId,
          mobile,
          rawMobile,
          payable,
          charges,
          null,
          match,
          connectionId,
          json.writeValueAsString(payload));

      sourceRows++;
      totalPayable = totalPayable.add(payable);
      totalCharges = totalCharges.add(charges);

      if (connectionId == null) {
        db.update(
            "insert into quality_issue(import_batch_id,issue_type,severity,source_sheet,source_row,mobile_norm,message) values(?,?,?,?,?,?,?)",
            batchId,
            "unmatched_billing_number",
            "ERROR",
            invoice.getSheetName(),
            r + 1,
            mobile,
            "Invoice number does not exist in the corporate connection master.");
      }

      if (!isStandardLocalPhone(mobile)) {
        db.update(
            "insert into quality_issue(import_batch_id,issue_type,severity,source_sheet,source_row,mobile_norm,message) values(?,?,?,?,?,?,?)",
            batchId,
            "nonstandard_mobile_number",
            "WARN",
            invoice.getSheetName(),
            r + 1,
            mobile,
            "Invoice Mobile No is not a standard 10-digit local number; verify before matching.");
      }
    }

    if (sourceRows == 0) {
      throw new IllegalArgumentException("Invoice contains no billing detail rows below the Mobile No header");
    }

    assertTotal("Charges for Bill Period", totalCharges, summaryCharges);
    assertTotal("Total Amount Payable", totalPayable, summaryPayable);

    db.update(
        "update import_batch set status='COMPLETED', source_rows=?, bill_total=? where id=?",
        sourceRows, totalPayable, batchId);

    Map<String,Object> result = new LinkedHashMap<>();
    result.put("batchId", batchId);
    result.put("billingBatchId", billingBatchId);
    result.put("format", "DIALOG_INVOICE");
    result.put("invoiceNumber", invoiceNumber);
    result.put("invoiceDate", invoiceDate);
    result.put("corporateCode", corporateCode);
    result.put("billPeriod", billPeriod);
    result.put("periodEnd", effectivePeriodEnd);
    result.put("sourceRows", sourceRows);
    result.put("billTotal", totalPayable);
    result.put("chargesForBillPeriod", totalCharges);
    result.put("summaryBillTotal", summaryPayable);
    result.put("summaryChargesForBillPeriod", summaryCharges);
    result.put("idempotent", false);
    return result;
  }

  private Map<String,Object> importLegacyCombined(
      MultipartFile file,
      String hash,
      Workbook wb,
      LocalDate periodEnd,
      String contractNo) throws Exception {

    List<Map<String,Object>> existing = db.queryForList(
        "select i.id, bb.id as billing_batch_id from import_batch i left join billing_batch bb on bb.import_batch_id=i.id where i.source_hash=? and i.period_end is not distinct from ? and i.contract_no is not distinct from ? order by i.id desc limit 1",
        hash, periodEnd, blankToNull(contractNo));
    if (!existing.isEmpty()) {
      Map<String,Object> result = new LinkedHashMap<>();
      result.put("batchId", existing.get(0).get("id"));
      result.put("billingBatchId", existing.get(0).get("billing_batch_id"));
      result.put("format", "LEGACY_COMBINED");
      result.put("idempotent", true);
      return result;
    }

    Sheet master = requireSheet(wb, "Co-operate Numbers");
    Sheet bill = requireSheet(wb, "Downloaded Excel");
    int billHeader = findHeader(bill, "Mobile", 20);
    Map<String,Integer> billHeaders = headers(bill, billHeader);
    validateHeaders(billHeaders, REQUIRED_LEGACY_BILL_HEADERS, "Downloaded Excel");

    long batchId = db.queryForObject(
        "insert into import_batch(filename,source_hash,period_end,contract_no,status) values(?,?,?,?,?) returning id",
        Long.class, file.getOriginalFilename(), hash, periodEnd, blankToNull(contractNo), "IMPORTING");
    long billingBatchId = db.queryForObject(
        "insert into billing_batch(import_batch_id,period_end,contract_no) values(?,?,?) returning id",
        Long.class, batchId, periodEnd, blankToNull(contractNo));

    int sourceRows = 0;
    BigDecimal total = BigDecimal.ZERO;

    for (int r = 4; r <= master.getLastRowNum(); r++) {
      String mobile = normalizePhone(value(master, r, 2));
      if (mobile == null || !mobile.matches(".*\\d.*")) continue;
      sourceRows++;
      upsertConnection(
          batchId,
          mobile,
          value(master, r, 2),
          value(master, r, 3),
          value(master, r, 4),
          value(master, r, 5),
          value(master, r, 6),
          value(master, r, 7),
          value(master, r, 8),
          value(master, r, 9),
          "corporate_master",
          master.getSheetName(),
          r + 1);
    }

    Set<String> seenBillingMobiles = new HashSet<>();
    for (int r = billHeader + 1; r <= bill.getLastRowNum(); r++) {
      String rawMobile = cell(bill, r, billHeaders, "Mobile");
      String mobile = normalizePhone(rawMobile);
      if (mobile == null) continue;

      if (!seenBillingMobiles.add(mobile)) {
        throw new IllegalArgumentException(
            "Duplicate Mobile in Downloaded Excel: " + mobile + " at Excel row " + (r + 1));
      }

      String amountText = cell(bill, r, billHeaders, "Total Amount Payable");
      BigDecimal amount = num(amountText);
      if (amount == null) {
        throw new IllegalArgumentException(
            "Missing or invalid Total Amount Payable for Mobile " + mobile + " at Excel row " + (r + 1));
      }
      total = total.add(amount);

      Long connectionId = findConnection(mobile);
      String match = connectionId == null ? "unmatched" : "matched_voice";
      String companyPayText = cell(bill, r, billHeaders, "Company Pay");
      BigDecimal companyPay = null;
      if (connectionId != null && companyPayText != null && !companyPayText.isBlank()) {
        companyPay = num(companyPayText);
        if (companyPay == null) {
          throw new IllegalArgumentException(
              "Invalid Company Pay for Mobile " + mobile + " at Excel row " + (r + 1));
        }
      }

      db.update(
          "insert into billing_line(billing_batch_id,mobile_norm,mobile_display,total_amount_payable,charges_for_bill_period,company_pay,match_status,connection_id,raw_json) values(?,?,?,?,?,?,?,?,?::jsonb)",
          billingBatchId,
          mobile,
          rawMobile,
          amount,
          num(cell(bill, r, billHeaders, "Charges for Bill Period")),
          companyPay,
          match,
          connectionId,
          json.writeValueAsString(rowByHeaders(bill, r, billHeaders)));

      if (connectionId == null) {
        db.update(
            "insert into quality_issue(import_batch_id,issue_type,severity,source_sheet,source_row,mobile_norm,message) values(?,?,?,?,?,?,?)",
            batchId,
            "unmatched_billing_number",
            "ERROR",
            bill.getSheetName(),
            r + 1,
            mobile,
            "Billing mobile number does not exist in the corporate connection master.");
      }
    }

    db.update(
        "update import_batch set status='COMPLETED', source_rows=?, bill_total=? where id=?",
        sourceRows, total, batchId);

    Map<String,Object> result = new LinkedHashMap<>();
    result.put("batchId", batchId);
    result.put("billingBatchId", billingBatchId);
    result.put("format", "LEGACY_COMBINED");
    result.put("sourceRows", sourceRows);
    result.put("billTotal", total);
    result.put("idempotent", false);
    return result;
  }

  private Map<String,Object> existingDialogInvoice(
      String invoiceNumber,
      String corporateCode,
      String billPeriod) {
    if (blankToNull(invoiceNumber) == null || blankToNull(corporateCode) == null || blankToNull(billPeriod) == null) {
      return null;
    }
    List<Map<String,Object>> rows = db.queryForList(
        "select i.id, bb.id as billing_batch_id " +
        "from import_batch i " +
        "join billing_batch bb on bb.import_batch_id=i.id " +
        "join lateral (select raw_json from billing_line where billing_batch_id=bb.id order by id limit 1) x on true " +
        "where x.raw_json->>'_format'='DIALOG_INVOICE' " +
        "and x.raw_json->>'_invoiceNumber'=? " +
        "and x.raw_json->>'_corporateCode'=? " +
        "and x.raw_json->>'_billPeriod'=? " +
        "order by i.id desc limit 1",
        invoiceNumber, corporateCode, billPeriod);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private Map<String,Object> existingByHash(String hash) {
    List<Map<String,Object>> rows = db.queryForList(
        "select i.id, bb.id as billing_batch_id from import_batch i left join billing_batch bb on bb.import_batch_id=i.id where i.source_hash=? order by i.id desc limit 1",
        hash);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private void upsertConnection(
      long importBatchId,
      String mobile,
      String display,
      String user,
      String designation,
      String nic,
      String location,
      String status,
      String pack,
      String type,
      String source,
      String sheet,
      int row) throws Exception {

    Long connectionId = findConnection(mobile);
    if (connectionId == null) {
      connectionId = db.queryForObject(
          "insert into connection(mobile_norm,mobile_display,source_kind,user_name,designation,nic,location,status_raw,status_norm,package,connection_type) values(?,?,?,?,?,?,?,?,?,?,?) returning id",
          Long.class,
          mobile,
          display,
          source,
          blankToNull(user),
          blankToNull(designation),
          blankToNull(nic),
          blankToNull(location),
          blankToNull(status),
          statusNorm(status),
          blankToNull(pack),
          defaultIfBlank(type, "Voice"));
    } else {
      db.update(
          "update connection set mobile_display=coalesce(nullif(?,''),mobile_display), user_name=coalesce(nullif(?,''),user_name), designation=coalesce(nullif(?,''),designation), nic=coalesce(nullif(?,''),nic), location=coalesce(nullif(?,''),location), status_raw=coalesce(nullif(?,''),status_raw), status_norm=case when nullif(?, '') is null then status_norm else ? end, package=coalesce(nullif(?,''),package), connection_type=coalesce(nullif(?,''),connection_type), updated_at=now() where id=?",
          clean(display), clean(user), clean(designation), clean(nic), clean(location), clean(status), clean(status), statusNorm(status), clean(pack), clean(type), connectionId);
    }

    Map<String,Object> sourcePayload = new LinkedHashMap<>();
    sourcePayload.put("display", display);
    sourcePayload.put("user", user);
    sourcePayload.put("designation", designation);
    sourcePayload.put("nic", nic);
    sourcePayload.put("location", location);
    sourcePayload.put("status", status);
    sourcePayload.put("package", pack);
    sourcePayload.put("type", type);

    db.update(
        "insert into connection_source(import_batch_id,connection_id,mobile_norm,source_sheet,source_row,source_json) values(?,?,?,?,?,?::jsonb)",
        importBatchId, connectionId, mobile, sheet, row, json.writeValueAsString(sourcePayload));

    Integer count = db.queryForObject(
        "select count(*) from connection_source where import_batch_id=? and mobile_norm=?",
        Integer.class,
        importBatchId,
        mobile);

    if (count != null && count > 1) {
      Integer issueCount = db.queryForObject(
          "select count(*) from quality_issue where import_batch_id=? and issue_type='duplicate_master_number' and mobile_norm=?",
          Integer.class,
          importBatchId,
          mobile);
      if (issueCount == null || issueCount == 0) {
        db.update(
            "insert into quality_issue(import_batch_id,issue_type,severity,source_sheet,source_row,mobile_norm,message) values(?,?,?,?,?,?,?)",
            importBatchId,
            "duplicate_master_number",
            "ERROR",
            sheet,
            row,
            mobile,
            "Mobile appears in multiple master rows within this workbook; manual ownership review required.");
      }
    }
  }

  private Long findConnection(String mobile) {
    List<Long> ids = db.query(
        "select id from connection where mobile_norm=?",
        (rs, n) -> rs.getLong(1),
        mobile);
    return ids.isEmpty() ? null : ids.get(0);
  }

  private Sheet requireSheet(Workbook workbook, String name) {
    Sheet sheet = workbook.getSheet(name);
    if (sheet == null) throw new IllegalArgumentException("Required worksheet missing: " + name);
    return sheet;
  }

  private boolean hasHeader(Sheet sheet, String name, int maxRows) {
    try {
      findHeader(sheet, name, maxRows);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private int findHeader(Sheet sheet, String name, int maxRows) {
    for (int r = 0; r < Math.min(maxRows, sheet.getLastRowNum() + 1); r++) {
      if (headers(sheet, r).containsKey(name.toLowerCase(Locale.ROOT))) return r;
    }
    throw new IllegalArgumentException("Header not found in " + sheet.getSheetName() + ": " + name);
  }

  private void validateHeaders(Map<String,Integer> headers, Set<String> required, String sheetName) {
    List<String> missing = required.stream().filter(h -> !headers.containsKey(h)).sorted().toList();
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException(
          "Missing required column(s) in " + sheetName + ": " + String.join(", ", missing));
    }
  }

  private Map<String,Integer> headers(Sheet sheet, int rowNo) {
    Map<String,Integer> result = new LinkedHashMap<>();
    Row row = sheet.getRow(rowNo);
    if (row == null) return result;
    for (Cell c : row) {
      String v = clean(formatter.formatCellValue(c));
      if (v != null && !v.isBlank()) result.put(v.toLowerCase(Locale.ROOT), c.getColumnIndex());
    }
    return result;
  }

  private String value(Sheet sheet, int rowNo, int colNo) {
    Row row = sheet.getRow(rowNo);
    if (row == null) return null;
    Cell cell = row.getCell(colNo);
    return cell == null ? null : clean(formatter.formatCellValue(cell));
  }

  private String cell(Sheet sheet, int rowNo, Map<String,Integer> headers, String key) {
    Integer col = headers.get(key.toLowerCase(Locale.ROOT));
    return col == null ? null : value(sheet, rowNo, col);
  }

  private Map<String,Object> rowByHeaders(Sheet sheet, int rowNo, Map<String,Integer> headers) {
    Map<String,Object> result = new LinkedHashMap<>();
    for (Map.Entry<String,Integer> e : headers.entrySet()) {
      result.put(e.getKey(), value(sheet, rowNo, e.getValue()));
    }
    return result;
  }

  private String findLabelValue(Sheet sheet, String label, int beforeRow) {
    String target = label.trim().toLowerCase(Locale.ROOT);
    int limit = Math.min(beforeRow, sheet.getLastRowNum() + 1);
    for (int r = 0; r < limit; r++) {
      Row row = sheet.getRow(r);
      if (row == null) continue;
      for (Cell c : row) {
        String v = clean(formatter.formatCellValue(c));
        if (v == null || !v.toLowerCase(Locale.ROOT).equals(target)) continue;
        for (int col = c.getColumnIndex() + 1; col < Math.min(c.getColumnIndex() + 5, 30); col++) {
          String candidate = value(sheet, r, col);
          if (candidate != null && !candidate.isBlank()) return candidate;
        }
      }
    }
    return null;
  }

  private BigDecimal findLabelNumber(Sheet sheet, String label, int beforeRow) {
    return num(findLabelValue(sheet, label, beforeRow));
  }

  private LocalDate parseBillPeriodEnd(String billPeriod) {
    if (billPeriod == null || billPeriod.isBlank()) return null;
    String[] parts = billPeriod.split("-");
    if (parts.length < 2) return null;
    String end = parts[parts.length - 1].trim();
    try {
      return LocalDate.parse(end, DateTimeFormatter.ofPattern("dd/MM/uuuu"));
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private BigDecimal requiredNumber(String value, String field, String mobile, int excelRow) {
    BigDecimal n = num(value);
    if (n == null) {
      throw new IllegalArgumentException(
          "Missing or invalid " + field + " for Mobile No " + mobile + " at Excel row " + excelRow);
    }
    return n;
  }

  private void assertTotal(String label, BigDecimal detailTotal, BigDecimal summaryTotal) {
    if (summaryTotal == null) return;
    BigDecimal diff = detailTotal.subtract(summaryTotal).abs();
    if (diff.compareTo(TOTAL_TOLERANCE) > 0) {
      throw new IllegalArgumentException(
          label + " detail total " + detailTotal + " does not match invoice summary " + summaryTotal);
    }
  }

  private static boolean isStandardLocalPhone(String mobile) {
    return mobile != null && mobile.matches("0\\d{9}");
  }

  private static String normalizePhone(String value) {
    if (value == null || value.isBlank()) return null;
    String raw = NON_ID.matcher(value.trim().replaceAll("\\.0$", "")).replaceAll("");
    String digits = raw.replaceAll("[^0-9]", "");
    if (digits.isBlank()) return raw.toUpperCase(Locale.ROOT);
    if (digits.length() == 11 && digits.startsWith("94")) return "0" + digits.substring(2);
    if (digits.length() == 9) return "0" + digits;
    return digits;
  }

  private static BigDecimal num(String value) {
    try {
      return value == null || value.isBlank() ? null : new BigDecimal(value.replace(",", ""));
    } catch (Exception e) {
      return null;
    }
  }

  private static String statusNorm(String value) {
    if (value == null || value.isBlank()) return "unknown";
    String v = value.trim().toLowerCase(Locale.ROOT);
    if (Set.of("active", "actve", "avtive").contains(v)) return "active";
    if (v.startsWith("dis")) return "disconnected";
    return v;
  }

  private static String clean(String value) {
    return value == null ? null : value.trim();
  }

  private static String blankToNull(String value) {
    String v = clean(value);
    return v == null || v.isBlank() ? null : v;
  }

  private static String defaultIfBlank(String value, String fallback) {
    String v = blankToNull(value);
    return v == null ? fallback : v;
  }

  private static String firstNonBlank(String a, String b) {
    String x = blankToNull(a);
    return x != null ? x : blankToNull(b);
  }

  private static String hex(byte[] bytes) {
    StringBuilder s = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) s.append(String.format("%02x", b));
    return s.toString();
  }
}
