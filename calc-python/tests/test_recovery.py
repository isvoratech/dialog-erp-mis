from decimal import Decimal
from app import calculate, RecoveryInput

def test_standard_recovery():
    r=calculate(RecoveryInput(bill="1000.00", companyPay="250.00", otherCharges="0"))
    assert r.recoveryAmount == Decimal("750.00") and r.companyPayable == Decimal("1000.00")

def test_other_charge_floor():
    r=calculate(RecoveryInput(bill="100.00", companyPay="200.00", otherCharges="35.55"))
    assert r.recoveryAmount == Decimal("35.55")

def test_negative_requires_review():
    r=calculate(RecoveryInput(bill="-10.25", matchStatus="matched_voice"))
    assert r.recoveryAmount == Decimal("0.00") and r.requiresReview and "negative_bill" in r.reviewReasons
