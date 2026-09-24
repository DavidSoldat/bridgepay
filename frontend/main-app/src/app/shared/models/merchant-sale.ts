export interface MerchantSaleResponse {
  id: string;
  createdAt: string;
  amount: number;
  status: string;
  installmentCount: number | null;
  installmentAmount: number | null;
  decisionAt: string | null;
}
