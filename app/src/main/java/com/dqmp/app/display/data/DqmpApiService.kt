package com.dqmp.app.display.data

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface DqmpApiService {
    @GET("api/queue/outlet/{id}")
    suspend fun getDisplayData(@Path("id") outletId: String): DisplayData

    @GET("api/queue/outlet/{id}/counters")
    suspend fun getCounterStatus(@Path("id") outletId: String): List<CounterStatus>

    @GET("api/branch-status/{id}")
    suspend fun getBranchStatus(@Path("id") outletId: String): BranchStatusResponse
}
