export interface NotificationItem {
  id: string;
  type: string;
  title: string;
  body: string;
  applicationId: string | null;
  createdAt: string;
  unread: boolean;
}

export interface NotificationPage {
  items: NotificationItem[];
  unreadCount: number;
  page: number;
  hasMore: boolean;
}
