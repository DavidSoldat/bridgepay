export interface MerchantSummaryResponse {
  totalCheckouts: number;
  approvedCount: number;
  inReviewCount: number;
  declinedCount: number;
  approvalRate: number | null;
  approvedVolume: number;
  feesPaid: number;
  netPaidOut: number;
  pendingPayout: number;
}
