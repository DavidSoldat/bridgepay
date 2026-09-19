export interface ApplicationResponse {
  applicationId: string;
  status: string;
  installmentCount: number | null;
  installmentAmount: number | null;
}
