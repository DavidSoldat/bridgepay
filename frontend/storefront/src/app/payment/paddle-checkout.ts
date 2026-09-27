import { Injectable, InjectionToken, inject } from '@angular/core';

/** Paddle client-side token from /config.json; public by design. Empty = payments disabled. */
export const PADDLE_CLIENT_TOKEN = new InjectionToken<string>('PADDLE_CLIENT_TOKEN');

export type CheckoutOutcome = 'completed' | 'closed';

interface PaddleEvent {
  name?: string;
}

interface PaddleJs {
  Environment: { set(environment: string): void };
  Initialize(options: { token: string; eventCallback: (event: PaddleEvent) => void }): void;
  Checkout: { open(options: { transactionId: string }): void; close(): void };
}

declare global {
  interface Window {
    Paddle?: PaddleJs;
  }
}

const PADDLE_JS_URL = 'https://cdn.paddle.com/paddle/v2/paddle.js';

@Injectable({ providedIn: 'root' })
export class PaddleCheckout {
  private readonly token = inject(PADDLE_CLIENT_TOKEN, { optional: true }) ?? '';
  private paddle?: Promise<PaddleJs>;
  private settle?: (outcome: CheckoutOutcome) => void;

  readonly enabled = this.token !== '';

  async open(transactionId: string): Promise<CheckoutOutcome> {
    this.paddle ??= this.load().catch((err) => {
      this.paddle = undefined; // let the next "Pay now" retry the script load
      throw err;
    });
    const paddle = await this.paddle;
    return new Promise((resolve) => {
      this.settle = resolve;
      paddle.Checkout.open({ transactionId });
    });
  }

  private async load(): Promise<PaddleJs> {
    const paddle = window.Paddle ?? (await loadScript());
    if (this.token.startsWith('test_')) {
      paddle.Environment.set('sandbox');
    }
    paddle.Initialize({ token: this.token, eventCallback: (event) => this.onEvent(event, paddle) });
    return paddle;
  }

  private onEvent(event: PaddleEvent, paddle: PaddleJs): void {
    if (event.name === 'checkout.completed') {
      this.finish('completed');
      paddle.Checkout.close();
    } else if (event.name === 'checkout.closed' || event.name === 'checkout.error') {
      // e.g. reopening a transaction already paid before its webhook landed: treat as not paid, never hang
      this.finish('closed');
    }
  }

  private finish(outcome: CheckoutOutcome): void {
    this.settle?.(outcome);
    this.settle = undefined;
  }
}

function loadScript(): Promise<PaddleJs> {
  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = PADDLE_JS_URL;
    script.onload = () =>
      window.Paddle ? resolve(window.Paddle) : reject(new Error('Paddle.js loaded without window.Paddle'));
    script.onerror = () => reject(new Error('Could not load Paddle.js'));
    document.head.appendChild(script);
  });
}
