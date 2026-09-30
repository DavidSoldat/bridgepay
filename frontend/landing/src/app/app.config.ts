import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { LANDING_CONFIG, LandingConfig } from './config';

export function appConfig(config: LandingConfig): ApplicationConfig {
  return {
    providers: [provideBrowserGlobalErrorListeners(), { provide: LANDING_CONFIG, useValue: config }],
  };
}
