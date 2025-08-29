package io.github.thibaultbee.streampack.app.sources.audio

import android.media.AudioRecord
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.StreamKey
import androidx.media3.common.TrackGroup
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.TrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import io.github.thibaultbee.streampack.app.ui.main.CircularPcmBuffer
import java.nio.ByteBuffer

/**
 * Custom MediaSource that uses AudioRecord as the audio source.
 */
class CustomAudioMediaSource(
    private val audioRecord: AudioRecord,
    private val buffer: CircularPcmBuffer,
    private val format: Format
) : BaseMediaSource() {

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        // Initialization logic if needed
    }

    override fun maybeThrowSourceInfoRefreshError() {
        // Handle errors if needed
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        return CustomAudioMediaPeriod(audioRecord, buffer, format)
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        // Release resources if needed
    }

    override fun releaseSourceInternal() {
        // Cleanup logic if needed
    }

    override fun getMediaItem() = throw UnsupportedOperationException("Not implemented")
}

/**
 * Custom MediaPeriod that reads audio data from AudioRecord and buffers it.
 */
class CustomAudioMediaPeriod(
    private val audioRecord: AudioRecord,
    private val buffer: CircularPcmBuffer,
    private val format: Format
) : MediaPeriod {

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        // Notify that the period is ready
        callback.onPrepared(this)
    }

    override fun maybeThrowPrepareError() {
        // Handle preparation errors if needed
    }

    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>) = emptyList<StreamKey>()

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        // Placeholder implementation for track selection
        return positionUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        // Discard buffer logic if needed
    }

    override fun readDiscontinuity(): Long {
        return C.TIME_UNSET
    }

    override fun getBufferedPositionUs(): Long {
        return C.TIME_END_OF_SOURCE
    }

    override fun seekToUs(positionUs: Long): Long {
        return positionUs
    }

    override fun isLoading(): Boolean {
        return false
    }

    override fun getNextLoadPositionUs(): Long {
        return C.TIME_END_OF_SOURCE
    }

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        return true
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long {
        // Placeholder implementation for adjusted seek position
        return positionUs
    }

    override fun reevaluateBuffer(positionUs: Long) {
        // Placeholder implementation for buffer reevaluation
    }

    override fun getTrackGroups(): TrackGroupArray {
        return TrackGroupArray(TrackGroup(format))
    }
}
