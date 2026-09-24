import { Routes } from '@angular/router';
import { Shop } from './shop/shop';
import { Account } from './account/account';

export const routes: Routes = [
  { path: '', component: Shop },
  { path: 'account', component: Account },
];
