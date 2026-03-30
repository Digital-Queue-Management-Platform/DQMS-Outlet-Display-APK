package com.dqmp.app.display.data

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

import retrofit2.Response

interface DqmpApiService {
    @GET("api/queue/outlet/{id}")
    suspend fun getDisplayData(@Path("id") outletId: String): Response<DisplayData>

    @GET("api/queue/outlet/{id}/counters")
    suspend fun getCounterStatus(@Path("id") outletId: String): Response<List<CounterStatus>>

    @GET("api/branch-status/{id}")
    suspend fun getBranchStatus(@Path("id") outletId: String): Response<BranchStatusResponse>
    
    @GET("api/teleshop-manager/check-device-config/{deviceId}")
    suspend fun checkDeviceConfig(@Path("deviceId") deviceId: String): Response<DeviceConfigResponse>
}
