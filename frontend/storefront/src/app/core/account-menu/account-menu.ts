import { Component, ElementRef, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icon } from '../../shared/ui/icon/icon';
import { Auth } from '../auth';

@Component({
  selector: 'app-account-menu',
  imports: [RouterLink, Icon],
  templateUrl: './account-menu.html',
  host: {
    class: 'relative',
    '(document:keydown.escape)': 'close(true)',
    '(document:click)': 'onDocumentClick($event)',
  },
})
export class AccountMenu {
  protected readonly auth = inject(Auth);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly button = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');

  protected readonly open = signal(false);

  protected toggle(): void {
    if (this.open()) this.close(false);
    else this.open.set(true);
  }

  protected close(returnFocus: boolean): void {
    if (!this.open()) return;
    this.open.set(false);
    if (returnFocus) this.button().nativeElement.focus();
  }

  protected onDocumentClick(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) this.close(false);
  }
}
