package hu.agocs.rxmp4parser.filters;

import org.mp4parser.muxer.Track;

import rx.functions.Func1;

public class VideoTrackFilter implements Func1<Track, Boolean> {

    private static final String VIDEO_TRACK = "vide";

    @Override
    public Boolean call(Track track) {
        return VIDEO_TRACK.equals(track.getHandler());
    }
}
