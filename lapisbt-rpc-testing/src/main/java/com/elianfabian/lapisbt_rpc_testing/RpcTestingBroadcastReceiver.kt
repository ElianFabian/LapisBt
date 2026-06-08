package com.elianfabian.lapisbt_rpc_testing

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.google.gson.Gson

class RpcTestingBroadcastReceiver : BroadcastReceiver() {

    private val gson = Gson()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "onReceive: $action")

        val rpcTestingApp = context.applicationContext as RpcTestingApplication
        val lapisBt = rpcTestingApp.lapisBt

        val resultValue: String = when (action) {
            "get-connected-devices" -> {
                gson.toJson(lapisBt.connectedDevices.value.map { it.address.value })
            }
            "get-last-rpc-result" -> {
                MainRpcTestingActivity.lastRpcResult.value ?: "null"
            }
            else -> "Unsupported action: $action"
        }

        setResult(Activity.RESULT_OK, resultValue, Bundle.EMPTY)
    }

    companion object {
        const val TAG = "RpcTestingBroadcastReceiver"
    }
}
