import { TestBed } from '@angular/core/testing';
import { PADDLE_CLIENT_TOKEN, PaddleCheckout } from './paddle-checkout';

function fakePaddle() {
  let callback: (event: { name?: string }) => void = () => {};
  return {
    Environment: { set: vi.fn() },
    Initialize: vi.fn((options: { eventCallback: (event: { name?: string }) => void }) => {
      callback = options.eventCallback;
    }),
    Checkout: { open: vi.fn(), close: vi.fn() },
    emit: (name: string) => callback({ name }),
  };
}

describe('PaddleCheckout', () => {
  afterEach(() => {
    delete (window as { Paddle?: unknown }).Paddle;
    document.head.querySelectorAll('script[src*="paddle.js"]').forEach((s) => s.remove());
  });

  function service(token?: string) {
    TestBed.configureTestingModule({
      providers: token === undefined ? [] : [{ provide: PADDLE_CLIENT_TOKEN, useValue: token }],
    });
    return TestBed.inject(PaddleCheckout);
  }

  it('is disabled without a client token', () => {
    expect(service().enabled).toBe(false);
    TestBed.resetTestingModule();
    expect(service('').enabled).toBe(false);
  });

  it('opens the transaction in sandbox and resolves completed, closing the overlay', async () => {
    const paddle = fakePaddle();
    (window as { Paddle?: unknown }).Paddle = paddle;
    const checkout = service('test_abc');

    const outcome = checkout.open('txn_1');
    await vi.waitFor(() => expect(paddle.Checkout.open).toHaveBeenCalledWith({ transactionId: 'txn_1' }));
    paddle.emit('checkout.completed');

    await expect(outcome).resolves.toBe('completed');
    expect(paddle.Environment.set).toHaveBeenCalledWith('sandbox');
    expect(paddle.Initialize).toHaveBeenCalledWith(expect.objectContaining({ token: 'test_abc' }));
    expect(paddle.Checkout.close).toHaveBeenCalled();
  });

  it('resolves closed when the shopper closes without paying, and initializes once across opens', async () => {
    const paddle = fakePaddle();
    (window as { Paddle?: unknown }).Paddle = paddle;
    const checkout = service('test_abc');

    const first = checkout.open('txn_1');
    await vi.waitFor(() => expect(paddle.Checkout.open).toHaveBeenCalledTimes(1));
    paddle.emit('checkout.closed');
    await expect(first).resolves.toBe('closed');

    const second = checkout.open('txn_1');
    await vi.waitFor(() => expect(paddle.Checkout.open).toHaveBeenCalledTimes(2));
    paddle.emit('checkout.closed');
    await expect(second).resolves.toBe('closed');
    expect(paddle.Initialize).toHaveBeenCalledTimes(1);
  });

  it('ignores the close that follows a completion', async () => {
    const paddle = fakePaddle();
    (window as { Paddle?: unknown }).Paddle = paddle;
    const checkout = service('test_abc');

    const outcome = checkout.open('txn_1');
    await vi.waitFor(() => expect(paddle.Checkout.open).toHaveBeenCalled());
    paddle.emit('checkout.completed');
    paddle.emit('checkout.closed');

    await expect(outcome).resolves.toBe('completed');
  });

  it('resolves closed when Paddle reports a checkout error, so the shopper is never stuck', async () => {
    const paddle = fakePaddle();
    (window as { Paddle?: unknown }).Paddle = paddle;
    const checkout = service('test_abc');

    const outcome = checkout.open('txn_already_paid');
    await vi.waitFor(() => expect(paddle.Checkout.open).toHaveBeenCalled());
    paddle.emit('checkout.error');

    await expect(outcome).resolves.toBe('closed');
  });

  it('does not force sandbox for a live token', async () => {
    const paddle = fakePaddle();
    (window as { Paddle?: unknown }).Paddle = paddle;
    const checkout = service('live_abc');

    void checkout.open('txn_1');
    await vi.waitFor(() => expect(paddle.Checkout.open).toHaveBeenCalled());
    expect(paddle.Environment.set).not.toHaveBeenCalled();
  });

  it('retries the script load after a failure', async () => {
    const checkout = service('test_abc');

    const first = checkout.open('txn_1');
    const script = document.head.querySelector<HTMLScriptElement>(
      'script[src="https://cdn.paddle.com/paddle/v2/paddle.js"]',
    );
    script!.onerror!(new Event('error'));
    await expect(first).rejects.toThrow('Could not load Paddle.js');

    void checkout.open('txn_1');
    expect(document.head.querySelectorAll('script[src*="paddle.js"]').length).toBe(2);
  });
});
