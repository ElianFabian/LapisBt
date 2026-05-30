package com.elianfabian.lapisbt_rpc_testing

import android.app.Application
import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt_rpc.LapisBtRpc

class RpcTestingApplication : Application() {

	lateinit var lapisBt: LapisBt
		private set

	lateinit var lapisBtRpc: LapisBtRpc
		private set


	override fun onCreate() {
		super.onCreate()

		lapisBt = LapisBt.newInstance(this)
		lapisBtRpc = LapisBtRpc.newInstance(lapisBt)
	}
}

val Application.rpcTestingApp: RpcTestingApplication get() = (this as RpcTestingApplication)
val Application.lapisBt: LapisBt get() = rpcTestingApp.lapisBt
val Application.lapisBtRpc: LapisBtRpc get() = rpcTestingApp.lapisBtRpc
