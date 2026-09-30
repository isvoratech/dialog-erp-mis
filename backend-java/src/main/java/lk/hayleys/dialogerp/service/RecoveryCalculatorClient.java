package lk.hayleys.dialogerp.service;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class RecoveryCalculatorClient {
  public record Input(
      BigDecimal bill,
      BigDecimal companyPay,
      BigDecimal otherCharges,
      String matchStatus,
      boolean ambiguous) {}

  public record Result(
      BigDecimal recoveryAmount,
      BigDecimal companyPayable,
      String formulaVersion,
      boolean requiresReview,
      List<String> reviewReasons) {}

  private final RestClient client;

  public RecoveryCalculatorClient(RestClient calculatorClient) {
    this.client = calculatorClient;
  }

  public Result calculate(Input input) {
    return client.post()
        .uri("/v1/recovery/calculate")
        .body(input)
        .retrieve()
        .body(Result.class);
  }
}
