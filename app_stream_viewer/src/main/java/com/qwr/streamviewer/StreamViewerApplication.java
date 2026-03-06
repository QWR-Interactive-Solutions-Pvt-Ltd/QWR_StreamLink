package com.qwr.streamviewer;

import android.app.Application;
import android.graphics.Bitmap;

public class StreamViewerApplication extends Application {

    private IStreamView streamView;

    public void setStreamView(IStreamView view) {
        streamView = view;
    }

    public void setStreamFrame(Bitmap bitmap) {
        if (streamView != null) {
            streamView.setStreamFrame(bitmap);
        }
    }

    public void onStreamStats(int fps, int frameSizeBytes) {
        if (streamView != null) {
            streamView.onStreamStats(fps, frameSizeBytes);
        }
    }
}

// ---------------------------------------------------------------------------
// Contract between DownstreamService/Application and the stream Activity
// ---------------------------------------------------------------------------
interface IStreamView {
    void setStreamFrame(Bitmap bitmap);
    void onStreamStats(int fps, int frameSizeBytes);
}
