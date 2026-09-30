import { Component, inject } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { DemoLogin, LANDING_CONFIG, Role } from '../config';
import { RoleCard } from './role-card';

/** How a walkthrough step names a role when the config has no login for it. */
const ROLE_PHRASE: Record<Role, string> = {
  shopper: 'the shopper',
  ops: 'an ops reviewer',
  merchant: 'the merchant',
};

@Component({
  selector: 'app-try-demo',
  imports: [RoleCard, NgTemplateOutlet],
  templateUrl: './try-demo.html',
})
export class TryDemo {
  protected readonly config = inject(LANDING_CONFIG);
  protected readonly roles: Role[] = ['shopper', 'ops', 'merchant'];

  protected login(role: Role): DemoLogin | undefined {
    return this.config.demoLogins.find((l) => l.role === role);
  }

  protected href(role: Role): string {
    return role === 'shopper' ? this.config.storefrontUrl : this.config.appUrl;
  }

  /** The username to sign in with, or a phrase for the role when there is none. */
  protected who(role: Role): { name: string; isLogin: boolean } {
    const login = this.login(role);
    return login ? { name: login.username, isLogin: true } : { name: ROLE_PHRASE[role], isLogin: false };
  }
}
