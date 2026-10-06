export interface OpsApplicant {
  subject: string;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  /** yyyy-MM-dd */
  dateOfBirth: string;
  /** Present on search results. */
  createdAt?: string;
}
