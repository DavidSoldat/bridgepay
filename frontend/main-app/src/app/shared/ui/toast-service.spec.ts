import { TestBed } from '@angular/core/testing';
import { TOAST_DURATION_MS, ToastService } from './toast-service';

describe('ToastService', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('stacks toasts and removes each one after the display time', () => {
    const service = TestBed.inject(ToastService);
    service.show('success', 'Saved');
    vi.advanceTimersByTime(1000);
    service.show('error', 'Broke');

    expect(service.toasts().map((t) => t.text)).toEqual(['Saved', 'Broke']);

    vi.advanceTimersByTime(TOAST_DURATION_MS - 1000);
    expect(service.toasts().map((t) => t.text)).toEqual(['Broke']);

    vi.advanceTimersByTime(1000);
    expect(service.toasts()).toEqual([]);
  });

  it('dismisses a toast early', () => {
    const service = TestBed.inject(ToastService);
    service.show('success', 'Saved');
    service.dismiss(service.toasts()[0].id);
    expect(service.toasts()).toEqual([]);
  });
});
