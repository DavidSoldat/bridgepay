import { Component, inject } from '@angular/core';
import { LANDING_CONFIG } from './config';
import { BridgepayMark } from './shared/ui/bridgepay-mark/bridgepay-mark';
import { Icon } from './shared/ui/icon/icon';
import { TryDemo } from './try-demo/try-demo';
import { ArchitectureDiagram } from './architecture/architecture-diagram';

@Component({
  selector: 'app-root',
  imports: [BridgepayMark, Icon, TryDemo, ArchitectureDiagram],
  templateUrl: './app.html',
})
export class App {
  protected readonly config = inject(LANDING_CONFIG);

  protected readonly steps = [
    { icon: 'shopping-bag', title: 'Checkout', text: "The shopper picks Pay in 4 at the merchant's checkout." },
    { icon: 'gauge', title: 'Score', text: 'A logistic-regression model scores the application in milliseconds from bureau data and repayment history.' },
    { icon: 'users', title: 'Decide', text: 'Clear cases are approved or declined automatically; borderline scores go to a human reviewer.' },
    { icon: 'wallet', title: 'Collect', text: 'The merchant is paid once the first installment clears; Paddle collects the rest weekly.' },
  ];

  protected readonly topics = [
    { title: 'The model', text: 'Logistic regression on 10 bureau features, exported to ONNX. Eval AUC-ROC 0.82. Every decision stores per-feature contributions, shown to reviewers.' },
    { title: 'Fails safe', text: 'Every external call has its own circuit breaker. If scoring fails, the application goes to manual review instead of being approved or declined blind.' },
    { title: 'Events', text: 'Decisions and repayments flow through Kafka via a transactional outbox. Events that fail after retries land in an ops retry queue.' },
    { title: 'Payments', text: 'Real Paddle sandbox: installment plans, signed webhooks, and reconciliation on read if a webhook never arrives.' },
  ];

  protected readonly stack = [
    'Java 21', 'Spring Boot 4', 'Kafka', 'PostgreSQL', 'Redis', 'Keycloak', 'ONNX Runtime', 'Angular 21', 'Tailwind', 'Kubernetes (k3s)',
  ];

  protected readonly installments = ['Today', 'Week 1', 'Week 2', 'Week 3'];
}
