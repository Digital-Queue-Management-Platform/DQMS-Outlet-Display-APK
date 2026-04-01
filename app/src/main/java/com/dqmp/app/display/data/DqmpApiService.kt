package com.dqmp.app.display.data

import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Body

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
    
    // HTTP Polling for audio events (WebSocket fallback)
    @GET("api/teleshop-manager/audio-events/{outletId}")
    suspend fun getAudioEvents(
        @Path("outletId") outletId: String,
        @Query("since") sinceTime: String
    ): Response<com.dqmp.app.display.viewmodel.AudioEventsResult>
    
    @POST("api/teleshop-manager/audio-events/{outletId}/ack")
    suspend fun acknowledgeAudioEvents(
        @Path("outletId") outletId: String,
        @Body eventIds: Map<String, Any>
    ): Response<Map<String, Any>>
}
