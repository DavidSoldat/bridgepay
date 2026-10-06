export interface ApplicantApplication {
  applicationId: string;
  merchantId: string;
  merchantName: string;
  amount: number;
  status: string;
  decisionSource: string | null;
  decidedBy: string | null;
  createdAt: string;
  decisionAt: string | null;
}

/** `limit`, `available` and `band` are null when the Credit Risk Engine couldn't be asked. */
export interface CreditStanding {
  limit: number | null;
  outstanding: number;
  available: number | null;
  band: string | null;
}

/** The repayment history the credit-risk policy overlay scores. */
export interface RepaymentHistory {
  completedPlans: number;
  defaultedPlans: number;
  latePaymentCount: number;
  onTimeRate: number;
}

export interface OpsInstallment {
  sequenceNumber: number;
  /** yyyy-MM-dd */
  dueDate: string;
  amount: number;
  status: string;
  paidAt: string | null;
  updatedAt: string;
}

export interface OpsPlan {
  planId: string;
  applicationId: string;
  status: string;
  totalAmount: number;
  installmentCount: number;
  installmentAmount: number;
  createdAt: string;
  updatedAt: string;
  installments: OpsInstallment[];
}

export interface ApplicantPlans {
  history: RepaymentHistory;
  plans: OpsPlan[];
}

export interface ShopperNotification {
  id: string;
  type: string;
  title: string;
  body: string;
  applicationId: string | null;
  createdAt: string;
}

export interface NotificationPage {
  items: ShopperNotification[];
  page: number;
  hasMore: boolean;
}
