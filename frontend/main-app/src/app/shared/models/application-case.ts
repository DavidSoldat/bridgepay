import { ScoreFactor } from './application';

export interface CaseDecision {
  /** Null = decided before decision records existed. */
  source: 'MODEL' | 'OPS' | null;
  decidedBy: string | null;
  reviewerNote: string | null;
}

export interface CaseMerchant {
  id: string;
  name: string;
  feeRatePct: number;
}

export interface CasePayout {
  amount: number;
  feeAmount: number;
  netAmount: number;
  status: string;
  paidAt: string | null;
}

export interface ApplicationCase {
  applicationId: string;
  applicantId: string;
  amount: number;
  status: string;
  riskScore: number | null;
  scoreFactors: ScoreFactor[];
  installmentCount: number | null;
  installmentAmount: number | null;
  createdAt: string;
  decisionAt: string | null;
  /** Null while the application awaits a decision. */
  decision: CaseDecision | null;
  merchant: CaseMerchant;
  payout: CasePayout | null;
}
