package com.girish.expensetracker

import android.app.Application
import com.girish.expensetracker.data.Repo
import com.girish.expensetracker.data.Store

class SpendApp : Application() {
    lateinit var repo: Repo
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repo(Store(this))
    }
}
