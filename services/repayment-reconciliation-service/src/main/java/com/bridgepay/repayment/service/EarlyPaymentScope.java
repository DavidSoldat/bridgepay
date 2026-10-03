package com.bridgepay.repayment.service;

public enum EarlyPaymentScope {
    /** The next scheduled installment. */
    NEXT,
    /** Every installment still scheduled - pays the plan off. */
    REMAINING
}
