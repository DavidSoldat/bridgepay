export interface ScoreFactor {
  feature: string;
  contribution: number;
}

export interface ApplicationResponse {
  applicationId: string;
  applicantId: string;
  merchantId: string;
  amount: number;
  status: string;
  riskScore: number | null;
  scoreFactors: ScoreFactor[];
  installmentCount: number | null;
  installmentAmount: number | null;
  decisionAt: string | null;
}
