package com.android.dialer.callrecord.impl;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;


/**
 * Encodes call audio with MediaCodec, then writing to container is done using either MediaMuxer
 * or written directly (AMR)
 * <p>
 * android.media.AudioRecord
 *  -> PCM data
 *  -> android.media.MediaCodec
 *  -> android.media.MediaMuxer or raw OutputStream to write to file
 * <p>
 * For simplicity, everything will be handled in one thread. Using asynchronous MediaCodec requires
 * a Handler, and the MediaCodec code is in a separate thread from the AudioThread code anyway.
 */
public class MediaCodecRecorder extends BaseCallRecorder {
  private static final String TAG = "MediaCodecRecorder";

  private AudioWriter mAudioWriter;
  protected final MediaCodec mMediaCodec;
  private final MediaFormat mMediaFormat;
  private final boolean mShouldWriteCodecSpecificData;

  /**
   *
   * @throws IllegalArgumentException
   * @throws IllegalStateException
   */
  public MediaCodecRecorder(Context context, int audioSource, Uri uri, OutputFormat outputFormat) {
    super(context, audioSource, uri, outputFormat);

    // FLAC header is written to csd-0 buffer
    mShouldWriteCodecSpecificData = outputFormat == OutputFormat.FLAC;

    switch (outputFormat) {
      case AAC_MPEG_4:
        mMediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                mAudioFormat.getSampleRate(), mAudioFormat.getChannelCount());
        break;
      case AMR_WB:
        mMediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_WB,
                mAudioFormat.getSampleRate(), mAudioFormat.getChannelCount());
        break;
      case FLAC:
        mMediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC,
                mAudioFormat.getSampleRate(), mAudioFormat.getChannelCount());
        mMediaFormat.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL,
                OutputFormat.FLAC_COMPRESSION_LEVEL);
        break;
      default:
        throw new IllegalArgumentException("unexpected output format " + outputFormat);
    }
    mMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, outputFormat.bitRate);

    final String encoderForFormat = new MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findEncoderForFormat(mMediaFormat);
    if (encoderForFormat == null) {
      throw new IllegalArgumentException("invalid format " + outputFormat);
    }
    Log.d(TAG, "found encoder " + encoderForFormat + " for format " + outputFormat);
    try {
      mMediaCodec = MediaCodec.createByCodecName(encoderForFormat);
    } catch (IOException | IllegalArgumentException e) {
      String msg = "failed to create codec for " + encoderForFormat;
      Log.e(TAG, msg, e);
      throw new IllegalArgumentException(msg, e);
    }
  }

  @Override
  protected void onRecordingStart(ParcelFileDescriptor pfd) throws IOException {
    switch (mOutputFormat) {
      case AAC_MPEG_4:
        mAudioWriter = new AudioWriter.MediaMuxerWrapper(pfd.getFileDescriptor(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        break;
      case AMR_WB:
        mAudioWriter = new AmrAudioWriter(pfd.getFileDescriptor());
        break;
      case FLAC:
        mAudioWriter = new AudioWriter.OutputStreamAudioWriter(pfd.getFileDescriptor());
        break;
      default:
        throw new IllegalStateException("unknown format " + mOutputFormat);
    }
    mMediaCodec.configure(mMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    mMediaCodec.start();
  }

  @Override
  protected void onPcmBufferRead(ByteBuffer pcmBuffer) throws IOException {
    int bytesEnqueued = 0;
    final int totalReadBefore = mBytesRead.get() - pcmBuffer.limit();
    pcmBuffer.position(0);
    while (pcmBuffer.hasRemaining()) {
      // From queueInputBuffer documentation:
      // "The presentation timestamp in microseconds for this buffer. This is normally the media time
      // at which this buffer should be presented (rendered)."
      long presentationTimeUs = computePresentationTimeUs(totalReadBefore + bytesEnqueued);
      int inputBufferIndex = mMediaCodec.dequeueInputBuffer(0);
      if (inputBufferIndex >= 0) {
        ByteBuffer inBuf = mMediaCodec.getInputBuffer(inputBufferIndex);
        if (inBuf != null) {
          inBuf.clear();
          // Fill as much as we can into inBuf
          final int bytesToQueue = Math.min(inBuf.capacity(), pcmBuffer.remaining());
          // Copy only bytesToQueue from pcmBuffer. ByteBuffer doesnt allow us to put in a certain
          // number of bytes from a source buffer
          final int oldLimit = pcmBuffer.limit();
          pcmBuffer.limit(pcmBuffer.position() + bytesToQueue);
          inBuf.put(pcmBuffer);
          pcmBuffer.limit(oldLimit);
          inBuf.flip();

          bytesEnqueued += bytesToQueue;

          int offset = 0;
          int flags = 0;
          mMediaCodec.queueInputBuffer(inputBufferIndex, offset, bytesToQueue, presentationTimeUs, flags);
        }
      } else {
        Log.d(TAG, " dequeueInputBuffer index is < 0 : " + inputBufferIndex
                + ", bytesEnqueued " + bytesEnqueued);
      }

      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      if (drainEncoder(info, false)) {
        Log.d(TAG, "drainEncoder eos reached, bytesEnqueued " + bytesEnqueued);
        break;
      }
    }
  }

  @Override
  protected void onRecordingStop() throws IOException {
    Log.d(TAG, "signalling end of stream");
    int inputBufferIndex = mMediaCodec.dequeueInputBuffer(10000);
    if (inputBufferIndex >= 0) {
      mMediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
    }
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    drainEncoder(info, true);

    if (Log.isLoggable("GosFlushFirst", Log.VERBOSE)) {
      Log.d(TAG, "GosFlushFirst");

      if (!Log.isLoggable("GosSkipFlushFirst", Log.VERBOSE)) {
        try {
          mMediaCodec.flush();
        } catch (IllegalStateException e) {
          Log.w(TAG, "reset: flush failed", e);
        }
      }

      int idx = mMediaCodec.dequeueInputBuffer(10000);
      if (idx >= 0) {
        Log.d(TAG, "enqueue input buffer for codec config");
        mMediaCodec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM | MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
      }

      MediaCodec.BufferInfo info1 = new MediaCodec.BufferInfo();
      idx = mMediaCodec.dequeueOutputBuffer(info1, 10000);
      mMediaCodec.getOutputBuffer(idx);
      boolean isConfig = (info1.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
      mMediaCodec.releaseOutputBuffer(idx, false);

      Log.d(TAG, "isConfig " + isConfig);

      try {
        ByteBuffer csd1 = mMediaCodec.getOutputFormat().getByteBuffer("csd-0");
        if (csd1 != null) {
          byte[] csdBytes = csd1.array();
          String csdBase64 = Base64.encodeToString(csdBytes, Base64.NO_WRAP);
          Log.d(TAG, "onRecordingStop: csdBase64: " + csdBase64);
          String csdDots = decodeUtf8WithDots(csdBytes);
          Log.d(TAG, "onRecordingStop: csdDots: " + csdDots);
        }
      } catch (Exception e) {
        Log.e(TAG, "Failed to get csd bytes");
      }
    }
  }

  private boolean drainEncoder(MediaCodec.BufferInfo info, boolean shouldLoopUntilEos)
          throws IOException {
    while (!Thread.currentThread().isInterrupted()) {
      int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(info, 0);
      if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        Log.w(TAG, "drainEncoder: INFO_OUTPUT_FORMAT_CHANGED");
        mAudioWriter.init(mMediaCodec.getOutputFormat());
      } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
        if (!shouldLoopUntilEos) {
          return false;
        }
        // Keep draining until end of stream
      } else if (outputBufferIndex >= 0) {
        ByteBuffer outBuf = mMediaCodec.getOutputBuffer(outputBufferIndex);
        try {
          if (outBuf == null) {
            Log.w(TAG, "drainEncoder: unexpected null output buffer");
            return false;
          }

          final boolean isEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
          Log.d(TAG, "drainEncoder: isEos " + isEos + ", bytes written " + mBytesRead.get());
          final boolean isCodecConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
          if (!isCodecConfig || mShouldWriteCodecSpecificData) {
            if (isCodecConfig) {
              Log.d(TAG, "drainEncoder: writing codec specific data, isEos " + isEos);
            }
            outBuf.position(info.offset);
            outBuf.limit(info.offset + info.size);
            mAudioWriter.write(outBuf, info);
          }

          if (shouldLoopUntilEos) {
            if (isEos) {
              return true;
            }
            // Loop
          } else {
            // Don't loop. Since input and output buffers are handled in same thread, go back to
            // exit drainEncoder loop and go back to processing input buffer
            return isEos;
          }
        } finally {
          mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);
        }
      }
    }
    return true;
  }

  public static String decodeWithDots(byte[] bytes, Charset charset) {
    CharsetDecoder decoder = charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    decoder.replaceWith(".");

    try {
      CharBuffer cb = decoder.decode(ByteBuffer.wrap(bytes));
      return cb.toString();
    } catch (CharacterCodingException e) {
      // Should not occur with REPLACE, but fallback just in case
      return new String(bytes, StandardCharsets.ISO_8859_1);
    }
  }

  // Convenience for UTF-8
  public static String decodeUtf8WithDots(byte[] bytes) {
    return decodeWithDots(bytes, StandardCharsets.UTF_8);
  }

  private void closeAudioWriter() {
    final AudioWriter writer = mAudioWriter;
    if (writer != null) {
      try {
        writer.close();
      } catch (Exception ignored) {
      }
      mAudioWriter = null;
    }
  }

  @Override
  protected void reset() {
    try {
      mMediaCodec.stop();
    } catch (IllegalStateException e) {
      Log.w(TAG, "reset: stop failed", e);
    }
    // Documentation says need to configure mMediaCodec again after stop
    closeAudioWriter();
  }

  @Override
  protected void onClose() {
    closeAudioWriter();

    try {
      mMediaCodec.stop();
    } catch (IllegalStateException ignored) {
    }
    mMediaCodec.release();
  }
}
