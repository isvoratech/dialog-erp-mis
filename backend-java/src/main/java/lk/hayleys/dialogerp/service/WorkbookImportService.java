package lk.hayleys.dialogerp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class WorkbookImportService {
  private static final Pattern NON_ID = Pattern.compile("[\\s\\-()]+" );
  private static final Set<String> REQUIRED_BILL_HEADERS = Set.of("mobile", "total amount payable");

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
    List<Map<String,Object>> existing = db.queryForList(
        "select id from import_batch where source_hash=? and period_end is not distinct from ? and contract_no is not distinct from ?",
        hash, periodEnd, contractNo);
    if (!existing.isEmpty()) return Map.of("batchId", existing.get(0).get("id"), "idempotent", true);

    try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
      Sheet master = requireSheet(wb, "Co-operate Numbers");
      Sheet bill = requireSheet(wb, "Downloaded Excel");
      int billHeader = findHeader(bill, "Mobile");
      Map<String,Integer> billHeaders = headers(bill, billHeader);
      validateHeaders(billHeaders, REQUIRED_BILL_HEADERS, "Downloaded Excel");

      long batchId = db.queryForObject(
          "insert into import_batch(filename,source_hash,period_end,contract_no,status) values(?,?,?,?,?) returning id",
          Long.class, file.getOriginalFilename(), hash, periodEnd, blankToNull(contractNo), "IMPORTING");
      long billingBatchId = db.queryForObject(
          "insert into billing_batch(import_batch_id,period_end,contract_no) values(?,?,?) returning id",
          Long.class, batchId, periodEnd, blankToNull(contractNo));

      int sourceRows = 0;
      BigDecimal total = BigDecimal.ZERO;

      for (int r = 4; r <= master.getLastRowNum(); r++) {
        String mobile = id(value(master, r, 2));
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
        String mobile = id(cell(bill, r, billHeaders, "Mobile"));
        if (mobile == null) continue;

        if (!seenBillingMobiles.add(mobile)) {
          throw new IllegalArgumentException(
              "Duplicate Mobile in Downloaded Excel: " + mobile + " at Excel row " + (r + 1));
        }

        BigDecimal amount = num(cell(bill, r, billHeaders, "Total Amount Payable"));
        if (amount == null) amount = BigDecimal.ZERO;
        total = total.add(amount);

        Long connectionId = findConnection(mobile);
        String match = connectionId == null ? "unmatched" : "matched_voice";
        BigDecimal companyPay = connectionId == null ? null : num(cell(bill, r, billHeaders, "Company Pay"));

        db.update(
            "insert into billing_line(billing_batch_id,mobile_norm,mobile_display,total_amount_payable,charges_for_bill_period,company_pay,match_status,connection_id,raw_json) values(?,?,?,?,?,?,?,?,?::jsonb)",
            billingBatchId,
            mobile,
            cell(bill, r, billHeaders, "Mobile"),
            amount,
            num(cell(bill, r, billHeaders, "Charges for Bill Period")),
            companyPay,
            match,
            connectionId,
            json.writeValueAsString(row(bill, r)));

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

      return Map.of(
          "batchId", batchId,
          "billingBatchId", billingBatchId,
          "sourceRows", sourceRows,
          "billTotal", total,
          "idempotent", false);
    }
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
          "update connection set mobile_display=coalesce(nullif(?,''),mobile_display), user_name=coalesce(nullif(?,''),user_name), designation=coalesce(nullif(?,''),designation), nic=coalesce(nullif(?,''),nic), location=coalesce(nullif(?,''),location), status_raw=coalesce(nullif(?,''),status_raw), status_norm=?, package=coalesce(nullif(?,''),package), connection_type=coalesce(nullif(?,''),connection_type), updated_at=now() where id=?",
          clean(display), clean(user), clean(designation), clean(nic), clean(location), clean(status), statusNorm(status), clean(pack), clean(type), connectionId);
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

  private void validateHeaders(Map<String,Integer> headers, Set<String> required, String sheetName) {
    List<String> missing = required.stream().filter(h -> !headers.containsKey(h)).sorted().toList();
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("Missing required column(s) in " + sheetName + ": " + String.join(", ", missing));
    }
  }

  private int findHeader(Sheet sheet, String name) {
    for (int r = 0; r < Math.min(20, sheet.getLastRowNum() + 1); r++) {
      if (headers(sheet, r).containsKey(name.toLowerCase(Locale.ROOT))) return r;
    }
    throw new IllegalArgumentException("Header not found in " + sheet.getSheetName() + ": " + name);
  }

  private Map<String,Integer> headers(Sheet sheet, int rowNo) {
    Map<String,Integer> result = new HashMap<>();
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

  private Map<String,Object> row(Sheet sheet, int rowNo) {
    Map<String,Object> result = new LinkedHashMap<>();
    Row row = sheet.getRow(rowNo);
    if (row == null) return result;
    for (Cell cell : row) {
      result.put(String.valueOf(cell.getColumnIndex()), clean(formatter.formatCellValue(cell)));
    }
    return result;
  }

  private static String statusNorm(String value) {
    if (value == null || value.isBlank()) return "unknown";
    String v = value.trim().toLowerCase(Locale.ROOT);
    if (Set.of("active", "actve", "avtive").contains(v)) return "active";
    if (v.startsWith("dis")) return "disconnected";
    return v;
  }

  private static String id(String value) {
    if (value == null || value.isBlank()) return null;
    return NON_ID.matcher(value.trim().replaceAll("\\.0$", ""))
        .replaceAll("")
        .toUpperCase(Locale.ROOT);
  }

  private static BigDecimal num(String value) {
    try {
      return value == null || value.isBlank() ? null : new BigDecimal(value.replace(",", ""));
    } catch (Exception e) {
      return null;
    }
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

  private static String hex(byte[] bytes) {
    StringBuilder s = new StringBuilder();
    for (byte b : bytes) s.append(String.format("%02x", b));
    return s.toString();
  }
}
