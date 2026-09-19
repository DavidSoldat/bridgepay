import { ComponentFixture, TestBed } from '@angular/core/testing';

import { NoAccess } from './no-access';
import { Auth } from '../core/auth';

describe('NoAccess', () => {
  let component: NoAccess;
  let fixture: ComponentFixture<NoAccess>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [NoAccess],
      providers: [{ provide: Auth, useValue: { logout: () => {} } }],
    }).compileComponents();

    fixture = TestBed.createComponent(NoAccess);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
