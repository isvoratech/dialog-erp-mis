from decimal import Decimal, ROUND_HALF_UP, InvalidOperation
from typing import List
from fastapi import FastAPI
from pydantic import BaseModel, Field, field_validator

MONEY = Decimal("0.01")
FORMULA_VERSION = "1.0"
app = FastAPI(title="Dialog ERP Recovery Calculator", version=FORMULA_VERSION)

class RecoveryInput(BaseModel):
    bill: Decimal = Field(ge=Decimal("-1000000000"), le=Decimal("1000000000"))
    companyPay: Decimal = Field(default=Decimal("0"), ge=Decimal("0"), le=Decimal("1000000000"))
    otherCharges: Decimal = Field(default=Decimal("0"), ge=Decimal("0"), le=Decimal("1000000000"))
    matchStatus: str = "matched_voice"
    ambiguous: bool = False

    @field_validator("bill", "companyPay", "otherCharges", mode="before")
    @classmethod
    def parse_decimal(cls, value):
        try: return Decimal(str(value))
        except (InvalidOperation, ValueError, TypeError): raise ValueError("Must be a valid number")

class RecoveryResult(BaseModel):
    recoveryAmount: Decimal
    companyPayable: Decimal
    formulaVersion: str
    requiresReview: bool
    reviewReasons: List[str]

@app.get("/health")
def health(): return {"status":"ok", "formulaVersion":FORMULA_VERSION}

@app.post("/v1/recovery/calculate", response_model=RecoveryResult)
def calculate(item: RecoveryInput):
    bill = item.bill.quantize(MONEY, rounding=ROUND_HALF_UP)
    company = item.companyPay.quantize(MONEY, rounding=ROUND_HALF_UP)
    other = item.otherCharges.quantize(MONEY, rounding=ROUND_HALF_UP)
    recovery = max(max(bill - company, Decimal("0")), other).quantize(MONEY, rounding=ROUND_HALF_UP)
    payable = max(bill, Decimal("0")).quantize(MONEY, rounding=ROUND_HALF_UP)
    reasons=[]
    if item.matchStatus == "unmatched": reasons.append("unmatched_billing_number")
    if item.ambiguous: reasons.append("ambiguous_connection_match")
    if bill < 0: reasons.append("negative_bill")
    return RecoveryResult(recoveryAmount=recovery, companyPayable=payable, formulaVersion=FORMULA_VERSION, requiresReview=bool(reasons), reviewReasons=reasons)
