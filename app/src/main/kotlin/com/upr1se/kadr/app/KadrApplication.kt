package com.upr1se.kadr.app

import android.app.Application

class KadrApplication : Application() {
  val container: AppContainer by lazy { AppContainer(this) }
}
