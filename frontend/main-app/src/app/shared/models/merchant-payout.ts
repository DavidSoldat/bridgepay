export interface MerchantPayoutResponse {
  id: string;
  applicationId: string;
  amount: number;
  feeAmount: number;
  status: string;
  paidAt: string | null;
}
