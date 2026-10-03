package com.bridgepay.repayment.client;

/** Paddle is up but refused a charge (declined card, past_due subscription, renewal lock, ...): nothing was charged. */
public class PaddlePaymentRefusedException extends RuntimeException {
    public PaddlePaymentRefusedException(String message, Throwable cause) {
        super(message, cause);
    }
}
