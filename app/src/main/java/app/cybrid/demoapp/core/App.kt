package app.cybrid.demoapp.core

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.cybrid.demoapp.BuildConfig
import app.cybrid.demoapp.api.Util
import app.cybrid.demoapp.api.auth.entity.TokenRequest
import app.cybrid.demoapp.api.auth.entity.TokenResponse
import app.cybrid.demoapp.api.auth.service.AppService
import app.cybrid.demoapp.listener.BearerListener
import app.cybrid.sdkandroid.Cybrid
import app.cybrid.sdkandroid.CybridEnv
import app.cybrid.sdkandroid.listener.CybridSDKEvents
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class App : Application(), CybridSDKEvents {

    private val tokenRequest = TokenRequest(
        client_id = BuildConfig.CLIENT_ID,
        client_secret = BuildConfig.CLIENT_SECRET
    )

    override fun onCreate() {

        super.onCreate()
        context = applicationContext

        setupCybridSDK()
    }

    fun setupCybridSDK() {

        Cybrid.instance.listener = this
        Cybrid.instance.env = demonEnv
        if (Cybrid.instance.customerGuid == "") {
            Cybrid.instance.customerGuid = BuildConfig.CUSTOMER_GUID
        }
    }

    override fun onTokenExpired() {

        Log.d(TAG, "onBearerExpired")
        this.getBearer()
    }

    override fun onEvent(level: Int, message: String) {

        if (level == Log.ERROR && context != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // -- Helper method to get the bearer
    fun getBearer(listener: BearerListener? = null, request: TokenRequest? = null) {

        val tokenService = Util.getClient().create(AppService::class.java)
        val token = request ?: this.tokenRequest
        tokenService.getBearer(token).enqueue(object : Callback<TokenResponse> {
            override fun onResponse(call: Call<TokenResponse>, response: Response<TokenResponse>) {

                if (response.isSuccessful) {

                    val tokenResponse:TokenResponse = response.body()!!
                    tokenResponse.let {

                        Log.d(TAG, "Bearer: " + it.accessToken)
                        Cybrid.instance.setBearer(it.accessToken)
                        listener?.onBearerReady()
                    }
                } else {

                    Log.d(TAG, "Error: " + response.raw())
                    listener?.onBearerError()
                }
            }

            override fun onFailure(call: Call<TokenResponse>, t: Throwable) {

                Log.d(TAG, "Error getting bearer token: " + t.message)
                listener?.onBearerError()
            }
        })
    }

    companion object {

        private val demonEnv = CybridEnv.SANDBOX
        private val demoUrl = "https://id.${demonEnv.name}.cybrid.app"
        const val TAG = "CybridSDKDemo"

        @SuppressLint("StaticFieldLeak")
        var context: Context? = null

        fun createVerticalRecyclerList(list: RecyclerView, context: Context?) {

            val layout = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            list.recycledViewPool.setMaxRecycledViews(0, 10)
            list.layoutManager = layout
            list.setItemViewCacheSize(10)
        }

        fun createRecyclerHorizontalList(list: RecyclerView, context: Context?) {

            val layout = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            list.recycledViewPool.setMaxRecycledViews(0, 10)
            list.layoutManager = layout
            list.setItemViewCacheSize(10)
        }
    }
}