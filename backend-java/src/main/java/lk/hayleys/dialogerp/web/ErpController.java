package lk.hayleys.dialogerp.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import lk.hayleys.dialogerp.service.*;

@RestController
@RequestMapping("/api")
public class ErpController {
  private final WorkbookImportService importer;
  private final RecoveryService recovery;
  private final DashboardService dashboard;
  private final BillingReportService billingReport;
  private final MonthlyWorkbookExportService workbookExport;

  public ErpController(
      WorkbookImportService importer,
      RecoveryService recovery,
      DashboardService dashboard,
      BillingReportService billingReport,
      MonthlyWorkbookExportService workbookExport) {
    this.importer = importer;
    this.recovery = recovery;
    this.dashboard = dashboard;
    this.billingReport = billingReport;
    this.workbookExport = workbookExport;
  }

  @GetMapping("/auth/me")
  public Map<String,Object> me(Authentication authentication) {
    return Map.of("username", authentication.getName(), "authorities", authentication.getAuthorities());
  }

  @GetMapping("/reports/dashboard")
  public Map<String,Object> dashboard() { return dashboard.dashboard(); }

  @GetMapping("/reports/billing/{billingBatchId}")
  public Map<String,Object> monthlyBilling(@PathVariable long billingBatchId) {
    return billingReport.monthlyBilling(billingBatchId);
  }

  @GetMapping("/reports/billing/{billingBatchId}/workbook")
  public ResponseEntity<byte[]> downloadMonthlyWorkbook(@PathVariable long billingBatchId) throws Exception {
    MonthlyWorkbookExportService.ExportedWorkbook x = workbookExport.build(billingBatchId);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + x.filename() + "\"")
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(x.bytes());
  }

  @PostMapping(value="/imports/workbook", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String,Object> upload(
      @RequestPart("file") MultipartFile file,
      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate periodEnd,
      @RequestParam(required=false) String contractNo) throws Exception {
    if (file.isEmpty()) throw new IllegalArgumentException("Workbook is empty");
    return importer.importWorkbook(file, periodEnd, contractNo);
  }

  @PostMapping("/billing/{billingBatchId}/recovery")
  public Map<String,Object> generate(@PathVariable long billingBatchId, Authentication authentication) {
    return recovery.generate(billingBatchId, authentication.getName());
  }

  @GetMapping("/recovery/review")
  public List<Map<String,Object>> review() { return recovery.reviewQueue(); }

  public record Decision(@NotBlank String decision,String note,String recoveryOverride,String companyPayableOverride) {}

  @PatchMapping("/recovery/{id}")
  public Map<String,Object> decide(@PathVariable long id,@Valid @RequestBody Decision body,Authentication authentication) {
    recovery.decide(id,body.decision(),body.note(),body.recoveryOverride(),body.companyPayableOverride(),authentication.getName());
    return Map.of("id", id, "status", body.decision().toUpperCase(Locale.ROOT));
  }
}
