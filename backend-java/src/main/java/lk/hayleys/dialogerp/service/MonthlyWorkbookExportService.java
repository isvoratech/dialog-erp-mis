package lk.hayleys.dialogerp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MonthlyWorkbookExportService {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final Path templatePath;
  private final DataFormatter formatter = new DataFormatter();

  public record ExportedWorkbook(byte[] bytes, String filename) {}

  public MonthlyWorkbookExportService(
      JdbcTemplate db,
      ObjectMapper json,
      @Value("${dialog.export.template-path:/home/saduai/mis/dialog/templates/dialog-numbers-template.xlsx}") String templatePath) {
    this.db = db;
    this.json = json;
    this.templatePath = Paths.get(templatePath);
  }

  public ExportedWorkbook build(long billingBatchId) throws Exception {
    if (!Files.isRegularFile(templatePath)) {
      throw new IllegalStateException("Monthly workbook template is not installed: " + templatePath);
    }

    Map<String,Object> batch;
    try {
      batch = db.queryForMap(
          "select bb.id as billing_batch_id, bb.period_end, bb.contract_no, i.filename, i.created_at " +
          "from billing_batch bb join import_batch i on i.id=bb.import_batch_id where bb.id=?",
          billingBatchId);
    } catch (EmptyResultDataAccessException e) {
      throw new IllegalArgumentException("Billing batch not found: " + billingBatchId);
    }

    List<Map<String,Object>> lines = db.queryForList(
        "select id,mobile_norm,mobile_display,total_amount_payable,charges_for_bill_period,raw_json " +
        "from billing_line where billing_batch_id=? order by id",
        billingBatchId);
    if (lines.isEmpty()) throw new IllegalArgumentException("Billing batch has no billing lines: " + billingBatchId);

    try (InputStream in = Files.newInputStream(templatePath); XSSFWorkbook wb = new XSSFWorkbook(in)) {
      Sheet master = requireSheet(wb, "Co-operate Numbers");
      Sheet downloaded = requireSheet(wb, "Downloaded Excel");
      Sheet recovery = requireSheet(wb, "Recovery HO");
      Sheet estate = requireSheet(wb, "Estate  Recovery");

      Map<String,MasterRow> masterByMobile = readMaster(master);
      writeDownloaded(downloaded, lines);
      writeRecovery(recovery, lines, masterByMobile, batch);
      writeEstateRecovery(estate, lines, masterByMobile, batch);

      wb.setForceFormulaRecalculation(true);
      try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        wb.write(out);
        LocalDate periodEnd = toDate(batch.get("period_end"));
        String suffix = periodEnd == null ? "cycle" : periodEnd.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        return new ExportedWorkbook(out.toByteArray(), "Dialog_Recovery_" + suffix + ".xlsx");
      }
    }
  }

  private void writeDownloaded(Sheet sheet, List<Map<String,Object>> lines) throws Exception {
    clearRows(sheet, 1); // Excel row 2 onward
    String[] headers = {
        "Mobile No","Previous Due Amount","Payment","Total Usage Charges","IDD","Roaming","VAS",
        "Discounts/Bill Adjustments","Balance Adjustments","Commitment Charges","Late Payment Charges",
        "Government Taxes","VAT","Add To Bill Charges","Charges for Bill Period","Total Amount Payable"
    };
    Row h = getOrCreateRow(sheet, 0);
    for (int c=0;c<headers.length;c++) getOrCreateCell(h,c).setCellValue(headers[c]);

    int r = 1;
    for (Map<String,Object> line : lines) {
      Map<String,Object> raw = raw(line.get("raw_json"));
      Row row = getOrCreateRow(sheet, r++);
      setText(row,0, Objects.toString(line.get("mobile_display"), Objects.toString(line.get("mobile_norm"), "")));
      setNumber(row,1, raw.get("previous due amount"));
      setNumber(row,2, raw.get("payment"));
      setNumber(row,3, raw.get("total usage charges"));
      setNumber(row,4, raw.get("idd"));
      setNumber(row,5, raw.get("roaming"));
      setNumber(row,6, raw.get("vas"));
      setNumber(row,7, raw.get("discounts/bill adjustments"));
      setNumber(row,8, raw.get("balance adjustments"));
      setNumber(row,9, raw.get("commitment charges"));
      setNumber(row,10, raw.get("late payment charges"));
      setNumber(row,11, raw.get("government taxes"));
      setNumber(row,12, raw.get("vat"));
      setNumber(row,13, raw.get("add to bill charges"));
      setNumber(row,14, line.get("charges_for_bill_period"));
      // Manual process uses current-period charge, not Dialog outstanding balance.
      setNumber(row,15, line.get("charges_for_bill_period"));
    }
  }

  private void writeRecovery(
      Sheet sheet,
      List<Map<String,Object>> lines,
      Map<String,MasterRow> masterByMobile,
      Map<String,Object> batch) throws Exception {
    clearRows(sheet, 3); // Excel row 4 onward
    Row title = getOrCreateRow(sheet,0);
    setText(title,0,"Mobile Connections - Dialog");
    Row period = getOrCreateRow(sheet,1);
    LocalDate end = toDate(batch.get("period_end"));
    setText(period,0,end == null ? "Monthly Recovery" : "For the Period of Ending " + end.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
    setText(period,10,"Contract No");
    setText(period,11,Objects.toString(batch.get("contract_no"),""));
    String[] headers={"Serial No","Emp No","User","Number","Type","Department","Total Bill","Limit","Oth/ Echann","Recovery Amount","Total Due","Amount Payable"};
    Row h=getOrCreateRow(sheet,2);
    for(int c=0;c<headers.length;c++) setText(h,c,headers[c]);

    int r=3;
    for(Map<String,Object> line:lines){
      String mobile=normalize(Objects.toString(line.get("mobile_norm"),""));
      MasterRow m=masterByMobile.get(mobile);
      Map<String,Object> raw=raw(line.get("raw_json"));
      BigDecimal totalBill=decimal(line.get("charges_for_bill_period"));
      BigDecimal other=decimal(raw.get("add to bill charges"));
      BigDecimal due=totalBill;
      BigDecimal payable=due.max(BigDecimal.ZERO);
      Row row=getOrCreateRow(sheet,r++);

      // Never invent a recovery for an unmatched Dialog number.
      // Keep the monthly charge visible, but require master-data review first.
      if(m==null){
        setText(row,0,"");
        setText(row,1,"");
        setText(row,2,"UNMATCHED - REVIEW");
        setText(row,3,Objects.toString(line.get("mobile_display"),mobile));
        setText(row,4,"");
        setText(row,5,"UNMATCHED");
        setNumber(row,6,totalBill);
        setText(row,7,"");
        setNumber(row,8,other);
        setText(row,9,"");
        setNumber(row,10,due);
        setNumber(row,11,payable);
        continue;
      }

      BigDecimal limit=m.companyPay;
      BigDecimal over=totalBill.subtract(limit).max(BigDecimal.ZERO);
      BigDecimal recovery=over.max(other);
      setText(row,0,m.serialNo);
      setText(row,1,m.empNo);
      setText(row,2,m.user);
      setText(row,3,Objects.toString(line.get("mobile_display"),mobile));
      setText(row,4,m.type);
      setText(row,5,m.department);
      setNumber(row,6,totalBill);
      setNumber(row,7,limit);
      setNumber(row,8,other);
      setNumber(row,9,recovery);
      setNumber(row,10,due);
      setNumber(row,11,payable);
    }
  }

  private void writeEstateRecovery(
      Sheet sheet,
      List<Map<String,Object>> lines,
      Map<String,MasterRow> masterByMobile,
      Map<String,Object> batch) {
    clearRows(sheet, 2); // rebuild from Excel row 3
    LocalDate end=toDate(batch.get("period_end"));
    Row title=getOrCreateRow(sheet,1);
    setText(title,0,"Dialog Voice Connections Estate Recovery" + (end==null?"":" - "+end.getYear()));

    // Match the manual pivot-style output structure.
    Row h=getOrCreateRow(sheet,2);
    setText(h,0,"Row Labels");
    setText(h,1,"Number");
    setText(h,2,"Sum of Total Bill");
    setText(h,3,"Sum of Total Due");

    Map<String,List<EstateLine>> groups=new LinkedHashMap<>();
    for(Map<String,Object> line:lines){
      MasterRow m=masterByMobile.get(normalize(Objects.toString(line.get("mobile_norm"),"")));
      if(m==null) continue;
      String dept=m.department==null||m.department.isBlank()?"Unassigned":m.department.trim();
      if(dept.equalsIgnoreCase("Head Office")) continue;
      BigDecimal amount=decimal(line.get("charges_for_bill_period"));
      String mobile=Objects.toString(line.get("mobile_display"),Objects.toString(line.get("mobile_norm"),""));
      groups.computeIfAbsent(dept,k->new ArrayList<>())
          .add(new EstateLine(mobile,m.type,m.user,amount));
    }

    int r=3;
    BigDecimal grandBill=BigDecimal.ZERO;
    BigDecimal grandDue=BigDecimal.ZERO;
    for(var e:groups.entrySet()){
      String dept=e.getKey();
      Row deptRow=getOrCreateRow(sheet,r++);
      setText(deptRow,0,dept);

      BigDecimal deptBill=BigDecimal.ZERO;
      BigDecimal deptDue=BigDecimal.ZERO;
      for(EstateLine item:e.getValue()){
        Row mobileRow=getOrCreateRow(sheet,r++);
        setText(mobileRow,1,item.mobile);

        Row typeRow=getOrCreateRow(sheet,r++);
        setText(typeRow,1,item.type);

        Row userRow=getOrCreateRow(sheet,r++);
        setText(userRow,1,item.user);
        setNumber(userRow,2,item.totalBill);
        // The manual Estate Recovery sheet carries the same signed current-period
        // amount into Total Due; negative credits must remain negative.
        setNumber(userRow,3,item.totalBill);

        deptBill=deptBill.add(item.totalBill);
        deptDue=deptDue.add(item.totalBill);
      }

      Row subtotal=getOrCreateRow(sheet,r++);
      setText(subtotal,0,dept+" Sum");
      setNumber(subtotal,2,deptBill);
      setNumber(subtotal,3,deptDue);
      r++; // blank row between estate groups, as in the manual workbook

      grandBill=grandBill.add(deptBill);
      grandDue=grandDue.add(deptDue);
    }

    Row total=getOrCreateRow(sheet,r);
    setText(total,0,"Grand Total");
    setNumber(total,2,grandBill);
    setNumber(total,3,grandDue);
  }

  private Map<String,MasterRow> readMaster(Sheet sheet){
    Map<String,MasterRow> map=new LinkedHashMap<>();
    for(int r=4;r<=sheet.getLastRowNum();r++){
      Row row=sheet.getRow(r); if(row==null) continue;
      String mobile=normalize(text(row,2)); if(mobile==null||mobile.isBlank()) continue;
      map.put(mobile,new MasterRow(
          text(row,13), text(row,14), text(row,3), text(row,6), text(row,15), decimal(text(row,11))));
    }
    return map;
  }

  private Map<String,Object> raw(Object value) throws Exception {
    if(value==null) return new LinkedHashMap<>();
    if(value instanceof Map<?,?> m){
      Map<String,Object> out=new LinkedHashMap<>();
      for(var e:m.entrySet()) out.put(Objects.toString(e.getKey(),""),e.getValue());
      return out;
    }
    return json.readValue(value.toString(), new TypeReference<Map<String,Object>>(){});
  }

  private Sheet requireSheet(Workbook wb,String name){
    Sheet s=wb.getSheet(name); if(s==null) throw new IllegalStateException("Template worksheet missing: "+name); return s;
  }
  private void clearRows(Sheet s,int start){
    for(int r=start;r<=s.getLastRowNum();r++){
      Row row=s.getRow(r); if(row==null) continue;
      for(Cell cell:row) cell.setBlank();
    }
  }
  private Row getOrCreateRow(Sheet s,int r){Row row=s.getRow(r);return row==null?s.createRow(r):row;}
  private Cell getOrCreateCell(Row r,int c){Cell cell=r.getCell(c);return cell==null?r.createCell(c):cell;}
  private void setText(Row r,int c,String v){getOrCreateCell(r,c).setCellValue(v==null?"":v);}
  private void setNumber(Row r,int c,Object v){getOrCreateCell(r,c).setCellValue(decimal(v).doubleValue());}
  private String text(Row r,int c){Cell cell=r.getCell(c);return cell==null?"":formatter.formatCellValue(cell).trim();}
  private static String normalize(String s){if(s==null)return null;String d=s.replaceAll("[^0-9]","");if(d.length()==11&&d.startsWith("94"))return "0"+d.substring(2);if(d.length()==9)return "0"+d;return d;}
  private static BigDecimal decimal(Object v){if(v==null)return BigDecimal.ZERO;try{return new BigDecimal(v.toString().replace(",","").trim());}catch(Exception e){return BigDecimal.ZERO;}}
  private static LocalDate toDate(Object v){if(v==null)return null;if(v instanceof java.sql.Date d)return d.toLocalDate();if(v instanceof LocalDate d)return d;try{return LocalDate.parse(v.toString());}catch(Exception e){return null;}}

  private record MasterRow(String serialNo,String empNo,String user,String department,String type,BigDecimal companyPay) {}
  private record EstateLine(String mobile,String type,String user,BigDecimal totalBill) {}
}
