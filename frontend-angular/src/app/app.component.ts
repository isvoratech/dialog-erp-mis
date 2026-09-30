import { Component } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
@Component({selector:'app-root',standalone:true,imports:[RouterLink,RouterOutlet],template:`<header><strong>Dialog ERP</strong><nav><a routerLink="/">Overview</a><a routerLink="/imports">Imports</a><a routerLink="/recovery">Recovery review</a></nav></header><main><router-outlet /></main>`})
export class AppComponent {}
