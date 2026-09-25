export interface FailedEvent {
  id: string;
  topic: string;
  messageKey: string | null;
  errorMessage: string;
  status: 'FAILED' | 'RETRYING' | 'RESOLVED';
  attempts: number;
  createdAt: string;
  updatedAt: string;
}
