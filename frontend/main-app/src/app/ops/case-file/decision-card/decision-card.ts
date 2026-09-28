import { Component, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { CaseDecision } from '../../../shared/models/application-case';
import { StatusBadge } from '../../../shared/ui/status-badge/status-badge';
import { Icon } from '../../../shared/ui/icon/icon';

@Component({
  selector: 'app-decision-card',
  imports: [DatePipe, StatusBadge, Icon],
  templateUrl: './decision-card.html',
  styleUrl: './decision-card.css',
})
export class DecisionCard {
  decision = input.required<CaseDecision>();
  status = input.required<string>();
  decisionAt = input<string | null>(null);
}
