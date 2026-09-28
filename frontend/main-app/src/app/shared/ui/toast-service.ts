import { Injectable, signal } from '@angular/core';

export type ToastKind = 'success' | 'error';

export interface Toast {
  id: number;
  kind: ToastKind;
  text: string;
}

export const TOAST_DURATION_MS = 4000;

@Injectable({ providedIn: 'root' })
export class ToastService {
  private nextId = 0;
  private readonly list = signal<Toast[]>([]);
  readonly toasts = this.list.asReadonly();

  show(kind: ToastKind, text: string): void {
    const id = this.nextId++;
    this.list.update((toasts) => [...toasts, { id, kind, text }]);
    setTimeout(() => this.dismiss(id), TOAST_DURATION_MS);
  }

  dismiss(id: number): void {
    this.list.update((toasts) => toasts.filter((t) => t.id !== id));
  }
}
