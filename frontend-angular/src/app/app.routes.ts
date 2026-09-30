import { Routes } from '@angular/router';
import { DashboardComponent } from './features/dashboard/dashboard.component';
import { ImportsComponent } from './features/imports/imports.component';
import { RecoveryComponent } from './features/recovery/recovery.component';
export const routes: Routes = [{path:'',component:DashboardComponent},{path:'imports',component:ImportsComponent},{path:'recovery',component:RecoveryComponent},{path:'**',redirectTo:''}];
