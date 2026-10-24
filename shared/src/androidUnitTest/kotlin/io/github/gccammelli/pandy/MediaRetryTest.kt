package io.github.gccammelli.pandy

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@androidx.annotation.OptIn(UnstableApi::class)
class MediaRetryTest {
    private val data = DataSpec(Uri.parse("https://home.example/stream/part.m4s"))
    private fun failure(error: IOException, count: Int = 3) = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo(1L, data, 0L), MediaLoadData(C.DATA_TYPE_MEDIA), error, count)
    private fun response(code: Int) = HttpDataSource.InvalidResponseCodeException(code, "test", null, emptyMap(), data, byteArrayOf())

    @Test fun earlyHlsPartsRetryQuicklyWithoutDiscardingTheOnlyStream() {
        val error = failure(response(416))
        assertEquals(300L, RetryEarlyParts.getRetryDelayMsFor(error))
        assertNull(RetryEarlyParts.getFallbackSelectionFor(LoadErrorHandlingPolicy.FallbackOptions(2, 0, 2, 0), error))
        assertEquals(10, RetryEarlyParts.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test fun realFailuresKeepNormalBackoffFallbackAndPermanentFailureHandling() {
        assertEquals(2000L, RetryEarlyParts.getRetryDelayMsFor(failure(IOException("offline"))))
        assertEquals(5000L, RetryEarlyParts.getRetryDelayMsFor(failure(IOException("offline"), 20)))
        assertEquals(C.TIME_UNSET, RetryEarlyParts.getRetryDelayMsFor(failure(FileNotFoundException("missing clip"))))
        val fallback = RetryEarlyParts.getFallbackSelectionFor(LoadErrorHandlingPolicy.FallbackOptions(2, 0, 2, 0), failure(response(404)))
        assertNotNull(fallback); assertEquals(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION, fallback.type)
        assertNull(RetryEarlyParts.getFallbackSelectionFor(LoadErrorHandlingPolicy.FallbackOptions(2, 0, 2, 0), failure(IOException("offline"))))
    }
}
