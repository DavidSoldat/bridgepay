export interface PlanInstallment {
  sequenceNumber: number;
  dueDate: string;
  amount: number;
  status: string;
  paidAt: string | null;
}

export interface RepaymentPlan {
  planId: string;
  applicationId: string;
  status: string;
  totalAmount: number;
  installmentCount: number;
  installmentAmount: number;
  installments: PlanInstallment[];
  checkoutTransactionId: string | null;
}
