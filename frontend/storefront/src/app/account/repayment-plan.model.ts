export interface Installment {
  sequenceNumber: number;
  dueDate: string;
  amount: number;
  status: string;
  paidAt: string | null;
}

export interface RepaymentPlanResponse {
  planId: string;
  applicationId: string;
  status: string;
  totalAmount: number;
  installmentCount: number;
  installmentAmount: number;
  installments: Installment[];
  /** Paddle transaction to pay installment 1 with; null once paid (or plan no longer active). */
  checkoutTransactionId: string | null;
}
