package com.android.dialer.callrecord.impl;

import android.media.AudioFormat;

/**
 * Permitted values for bitrate and sample rate can be found at
 * https://cs.android.com/android/platform/superproject/main/+/main:frameworks/av/media/libstagefright/data/media_codecs_google_c2_audio.xml
 * and other .xmls in the directory.
 * <p>
 * Note: As of Jan 2026, Google Dialer records with LPCM_WAV with a sample rate of 8000 and then
 * appears to resample it to 16000. Resampling is done in native code via a "QResampler" (Q31?),
 * though it converts the PCM data from bytes to floats in Java first (mapping each byte to
 * the closed interval [-1.0f, 1.0f]). Google Dialer does have MPEG_4 and FLAC implemented via
 * MediaCodec and MediaMuxer, but it doesn't appear to be used right now.
 */
public enum OutputFormat {
  AAC_MPEG_4(0, AudioFormat.CHANNEL_IN_MONO, 16000, 16000, ".m4a"),
  /**
   * Note: A sample rate of 16000 Hz is required for AMR-WB encoders on Android
   */
  AMR_WB(1, AudioFormat.CHANNEL_IN_MONO, 16000, 16000, ".amr"),
  LPCM_WAV(2, AudioFormat.CHANNEL_IN_MONO, 16000, 16000, ".wav"),
  FLAC(3, AudioFormat.CHANNEL_IN_MONO, 16000, 16000, ".flac");

  public static final int FLAC_COMPRESSION_LEVEL = 8;

  public final int selectionIdForPrefs;
  public final int channelMask;
  public final int bitRate;
  public final int sampleRate;
  public final String extension;

  OutputFormat(int selectionIdForPrefs, int channelMask, int bitRate, int sampleRate,
          String extension) {
    this.selectionIdForPrefs = selectionIdForPrefs;
    this.channelMask = channelMask;
    this.bitRate = bitRate;
    this.sampleRate = sampleRate;
    this.extension = extension;
  }

  public static OutputFormat getOutputFormat(int selectionIdForPrefs) {
    if (selectionIdForPrefs < 0 || selectionIdForPrefs >= values().length) {
      throw new IllegalArgumentException("unexpected output format " + selectionIdForPrefs);
    }
    final OutputFormat format = values()[selectionIdForPrefs];
    if (format.selectionIdForPrefs == selectionIdForPrefs) {
      return format;
    }
    // in case enums are reordered
    for (OutputFormat fmt : values()) {
      if (fmt.selectionIdForPrefs == selectionIdForPrefs) {
        return fmt;
      }
    }
    throw new IllegalArgumentException("unexpected output format " + selectionIdForPrefs);
  }
}
