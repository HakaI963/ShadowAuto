package com.silentauto.shell;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Range;
import android.view.Display;
import android.view.Surface;

import java.nio.ByteBuffer;

final class VideoStreamer implements Runnable {
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int I_FRAME_INTERVAL_SECONDS = 1;
    private static final long DRAIN_TIMEOUT_US = 100_000L;

    private final LogHub logs;
    private final String taskId;
    private final int width;
    private final int height;
    private final MediaCodec encoder;
    private final Surface inputSurface;
    private final Thread thread;

    private volatile boolean running;

    VideoStreamer(LogHub logs, String taskId, int width, int height) throws Exception {
        this.logs = logs;
        this.taskId = taskId == null ? "" : taskId;
        int targetFps = deviceRefreshRate();
        MediaCodecInfo.VideoCapabilities caps = encoderCapabilities();
        Prepared prepared = prepareBest(width, height, targetFps, caps);
        this.width = prepared.width;
        this.height = prepared.height;
        encoder = prepared.encoder;
        inputSurface = prepared.surface;
        thread = new Thread(this, "video-streamer");
        if (this.width != width || this.height != height) {
            logs.info(this.taskId, "video encoder size adjusted: " + width + "x" + height + " -> " + this.width + "x" + this.height);
        }
    }

    private Prepared prepareBest(int preferredWidth, int preferredHeight, int targetFps,
                                   MediaCodecInfo.VideoCapabilities caps) throws Exception {
        Exception last = null;
        int[][] candidates = candidates(preferredWidth, preferredHeight);
        for (int[] candidate : candidates) {
            int width = candidate[0];
            int height = candidate[1];
            int fps = targetFps;
            int bitrate = bitRateFor(width, height, targetFps);
            if (caps != null) {
                try {
                    if (!caps.isSizeSupported(width, height)) {
                        continue;
                    }
                    Range<Integer> fpsRange = caps.getSupportedFrameRatesFor(width, height);
                    fps = clamp(fps, fpsRange.getLower(), fpsRange.getUpper());
                    Range<Integer> bitrateRange = caps.getBitrateRange();
                    bitrate = clamp(bitRateFor(width, height, fps),
                            bitrateRange.getLower(), Math.min(bitrateRange.getUpper(), 15_000_000));
                } catch (Throwable ignored) {
                }
            }
            try {
                Prepared prepared = prepareOne(width, height, fps, bitrate);
                logs.info(this.taskId, "video encoder " + width + "x" + height
                        + "@" + fps + "fps " + (bitrate / 1000) + "kbps");
                return prepared;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new IllegalStateException("No video encoder size available") : last;
    }

    /**
     * Device-native refresh rate: the highest mode the default display
     * supports, clamped to a sane encoder range. No hardcoded fps cap.
     */
    private int deviceRefreshRate() {
        try {
            DisplayManager manager = (DisplayManager) ShellContext.get().getSystemService(Context.DISPLAY_SERVICE);
            Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) {
                return 60;
            }
            float best = display.getRefreshRate();
            try {
                for (Display.Mode mode : display.getSupportedModes()) {
                    if (mode != null) {
                        best = Math.max(best, mode.getRefreshRate());
                    }
                }
            } catch (Throwable ignored) {
            }
            return clamp(Math.round(best), 24, 120);
        } catch (Throwable ignored) {
            return 60;
        }
    }

    private MediaCodecInfo.VideoCapabilities encoderCapabilities() {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (MIME.equalsIgnoreCase(type)) {
                        return info.getCapabilitiesForType(type).getVideoCapabilities();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Prepared prepareOne(int width, int height, int fps, int bitrate) throws Exception {
        MediaCodec codec = MediaCodec.createEncoderByType(MIME);
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            return new Prepared(width, height, codec, codec.createInputSurface());
        } catch (Exception e) {
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
            throw e;
        }
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    Surface inputSurface() {
        return inputSurface;
    }

    void start() {
        running = true;
        encoder.start();
        requestKeyFrame();
        thread.start();
    }

    void stop() {
        running = false;
        try {
            encoder.signalEndOfInputStream();
        } catch (Throwable ignored) {
        }
        try {
            thread.join(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        release();
    }

    @Override
    public void run() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (running) {
                int index = encoder.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    logs.videoConfig(taskId, encoder.getOutputFormat());
                } else if (index >= 0) {
                    drainOutput(index, info);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        } catch (Throwable e) {
            logs.error(taskId, "video stream failed", e);
        }
    }

    private void drainOutput(int index, MediaCodec.BufferInfo info) {
        ByteBuffer data = encoder.getOutputBuffer(index);
        try {
            if (data == null || info.size <= 0) {
                return;
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                return;
            }
            byte[] bytes = new byte[info.size];
            data.position(info.offset);
            data.limit(info.offset + info.size);
            data.get(bytes);
            String sample = Base64.encodeToString(bytes, Base64.NO_WRAP);
            logs.videoSample(taskId, info.presentationTimeUs, info.flags, sample);
        } finally {
            encoder.releaseOutputBuffer(index, false);
        }
    }

    private void requestKeyFrame() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return;
        }
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encoder.setParameters(params);
        } catch (Throwable ignored) {
        }
    }

    private void release() {
        try {
            encoder.stop();
        } catch (Throwable ignored) {
        }
        try {
            encoder.release();
        } catch (Throwable ignored) {
        }
        try {
            inputSurface.release();
        } catch (Throwable ignored) {
        }
    }

    private static int bitRateFor(int width, int height, int fps) {
        long pixels = (long) width * height;
        long target = pixels * Math.max(1, fps) / 4;
        return (int) Math.max(900_000L, Math.min(12_000_000L, target));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int even(int value) {
        if (value <= 0) {
            return 2;
        }
        if ((value & 1) == 0) {
            return value;
        }
        return value - 1;
    }

    private static int alignDown(int value, int align) {
        int aligned = value / align * align;
        if (aligned < align) {
            return align;
        }
        return aligned;
    }

    private static int[][] candidates(int width, int height) {
        int exactWidth = even(width);
        int exactHeight = even(height);
        int alignedWidth = alignDown(exactWidth, 16);
        int alignedHeight = alignDown(exactHeight, 16);
        int[] scaled = scaleMaxSide(exactWidth, exactHeight, 1920);
        return new int[][]{
                {exactWidth, exactHeight},
                {alignedWidth, alignedHeight},
                scaled
        };
    }

    private static int[] scaleMaxSide(int width, int height, int maxSide) {
        int max = Math.max(width, height);
        if (max <= maxSide) {
            return new int[]{width, height};
        }
        float ratio = (float) maxSide / max;
        return new int[]{alignDown(Math.round(width * ratio), 16), alignDown(Math.round(height * ratio), 16)};
    }

    private static final class Prepared {
        final int width;
        final int height;
        final MediaCodec encoder;
        final Surface surface;

        Prepared(int width, int height, MediaCodec encoder, Surface surface) {
            this.width = width;
            this.height = height;
            this.encoder = encoder;
            this.surface = surface;
        }
    }
}
